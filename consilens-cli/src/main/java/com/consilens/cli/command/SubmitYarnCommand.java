package com.consilens.cli.command;

import com.consilens.cluster.api.ClusterApplicationResult;
import com.consilens.cluster.api.ClusterComparisonDescription;
import com.consilens.cluster.api.ClusterExecutionDescription;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.YarnSubmissionSpec;
import com.consilens.connector.api.planner.ExecutionMode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Submits one compare request to a YARN cluster through the public cluster
 * submission SPI, adopting the spark-submit patterns that transfer to a
 * comparison job: the descriptor is passed positionally like the application
 * jar, every option can be defaulted through {@code --properties-file} or
 * {@code --conf} with {@code consilens.yarn.*} keys shaped like their
 * {@code spark.yarn.*} counterparts (archive, stagingDir, maxAppAttempts,
 * queue, tags), memory accepts spark's {@code 1g}/{@code 2048m} format, and the
 * client blocks until the application finishes. There is deliberately no
 * {@code --master}/{@code --deploy-mode}: the subcommand itself is the backend
 * and a comparison has no driver/executor split.
 */
@Command(
        name = "yarn",
        description = "Submit a comparison to YARN (consilens submit yarn [options] <descriptor>)",
        mixinStandardHelpOptions = true
)
public class SubmitYarnCommand implements Callable<Integer> {

    static final String CONF_PREFIX = "consilens.yarn.";
    private static final Pattern MEMORY_FORMAT = Pattern.compile("^(\\d+)\\s*([bkmgt]?)$", Pattern.CASE_INSENSITIVE);

    private final Supplier<ClusterSubmitter> submitterFactory;
    private final Supplier<String> submissionIdFactory;

    @Option(names = {"-c", "--config"},
            description = "Optional local descriptor reference retained for compatibility; cluster runtime reads the descriptor")
    private String configFile;

    @Option(names = "--name", description = "Application name (default: consilens-<submission id>)")
    private String applicationName;

    @Option(names = "--queue", description = "YARN queue (default: default)")
    private String queue;

    @Option(names = "--am-memory",
            description = "AM container memory in spark format: 1g, 2048m, 512M (default: 1g)")
    private String amMemory;

    @Option(names = "--am-vcores", description = "AM container virtual cores (default: 1)")
    private Integer amVCores;

    @Option(names = "--runtime-archive",
            description = "Remote URI or local path of the runtime archive, like spark.yarn.archive; "
                    + "local paths are staged to consilens.yarn.stagingDir")
    private String runtimeArchiveUri;

    @Option(names = "--descriptor-uri",
            description = "Remote URI or local path of the redacted submission descriptor; "
                    + "alternative to passing the descriptor positionally")
    private String descriptorUri;

    @Option(names = "--files",
            description = "Comma-separated local paths or remote URIs shipped into the AM container and reachable by file name, "
                    + "with optional #alias (like spark --files); remote URIs are not re-uploaded")
    private String files;

    @Option(names = "--jars",
            description = "Comma-separated local jars or remote URIs localized into the AM container and appended to the AM classpath, "
                    + "with optional #alias (like spark --jars)")
    private String jars;

    @Option(names = "--properties-file",
            description = "Properties file with consilens.yarn.* defaults; CLI flags override these values")
    private String propertiesFile;

    @Option(names = "--conf", description = "Override a single consilens.yarn.* property as key=value; repeatable")
    private Map<String, String> confOverrides = new LinkedHashMap<>();

    @Parameters(index = "0", arity = "0..1",
            description = "Descriptor file (local path or URI), the consilens equivalent of spark-submit's application jar")
    private String descriptorParameter;

    @Spec
    private CommandSpec commandSpec;

    public SubmitYarnCommand() {
        this(SubmitYarnCommand::createDefaultSubmitter, () -> UUID.randomUUID().toString());
    }

    SubmitYarnCommand(Supplier<ClusterSubmitter> submitterFactory,
                      Supplier<String> submissionIdFactory) {
        this.submitterFactory = submitterFactory;
        this.submissionIdFactory = submissionIdFactory;
    }

    @Override
    public Integer call() {
        ClusterSubmitter submitter = null;
        try {
            String descriptor = resolveExclusive(descriptorParameter, descriptorUri,
                    "descriptor", "--descriptor-uri");
            requireValue(descriptor != null, "descriptor is required, like spark-submit's application jar");
            Map<String, String> settings = loadSettings();
            String submissionId = submissionIdFactory.get();
            int maxAttempts = integerSetting(null, settings.get("maxAppAttempts"), -1);
            ClusterSubmitRequest submissionRequest = ClusterSubmitRequest.builder()
                    .submissionId(submissionId)
                    .comparison(ClusterComparisonDescription.builder()
                            .sourceConfigRef("descriptor:" + descriptor)
                            .targetConfigRef("descriptor:" + descriptor)
                            .build())
                    .execution(ClusterExecutionDescription.builder()
                            .executionMode(ExecutionMode.YARN)
                            .maxAttempts(maxAttempts == -1 ? null : maxAttempts)
                            .yarnQueue(queue)
                            .build())
                    .build();
            submissionRequest.setYarnSubmission(YarnSubmissionSpec.builder()
                    .runtimeArchiveUri(firstNonBlank(runtimeArchiveUri, settings.get("archive")))
                    .descriptorUri(descriptor)
                    .stagingUri(settings.get("stagingDir"))
                    .applicationName(firstNonBlank(applicationName, settings.get("name"),
                            "consilens-" + submissionId))
                    .queue(firstNonBlank(queue, settings.get("queue")))
                    .amMemoryMb(memorySetting(settings))
                    .amVCores(integerSetting(amVCores, settings.get("amVCores"), 1))
                    .tags(tagsFrom(settings))
                    .files(listSetting(files, settings.get("files")))
                    .jars(listSetting(jars, settings.get("jars")))
                    .build());
            validateSubmissionRequest(submissionRequest, maxAttempts);
            submitter = submitterFactory.get();
            ClusterSubmission submission = submitter.submit(submissionRequest);
            commandSpec.commandLine().getOut().println(
                    "YARN application submitted: " + submission.getClusterApplicationId());
            return waitForCompletion(settings, submitter, submission);
        } catch (Exception e) {
            PrintWriter errorWriter = commandSpec.commandLine().getErr();
            if (e instanceof IllegalArgumentException) {
                // Validation failures raise IllegalArgumentException with our own
                // static messages before any credentials enter the flow.
                errorWriter.println("YARN submission failed: " + e.getMessage());
                if (System.getenv("CONSILENS_DEBUG") != null) {
                    e.printStackTrace(errorWriter);
                }
            } else {
                // Runtime exception messages may embed connector credentials; only
                // the class name is echoed, the full stack needs CONSILENS_DEBUG.
                errorWriter.println("YARN submission failed: " + e.getClass().getName());
                if (System.getenv("CONSILENS_DEBUG") != null) {
                    e.printStackTrace(errorWriter);
                }
            }
            return 1;
        } finally {
            closeQuietly(submitter);
        }
    }

    private Integer waitForCompletion(Map<String, String> settings,
                                      ClusterSubmitter submitter,
                                      ClusterSubmission submission) {
        if (!booleanSetting(settings.get("submit.waitAppCompletion"), true)) {
            return 0;
        }
        ClusterApplicationResult result = submitter.awaitCompletion(submission, null);
        if (result == null) {
            // The backend cannot observe completion; the tracking UI remains
            // the source of truth, like spark.yarn.submit.waitAppCompletion=false.
            return 0;
        }
        PrintWriter out = commandSpec.commandLine().getOut();
        out.println("YARN application finished: " + result.getApplicationId()
                + " finalStatus=" + result.getFinalStatus()
                + (result.getTrackingUrl() == null ? "" : " trackingUrl=" + result.getTrackingUrl()));
        if (!result.succeeded()) {
            commandSpec.commandLine().getErr().println(
                    "YARN application did not succeed: finalStatus=" + result.getFinalStatus());
            return 1;
        }
        return 0;
    }

    private Map<String, String> loadSettings() throws IOException {
        Map<String, String> settings = new LinkedHashMap<>();
        if (propertiesFile != null) {
            Properties properties = new Properties();
            try (InputStream inputStream = Files.newInputStream(Path.of(propertiesFile))) {
                properties.load(inputStream);
            }
            properties.forEach((name, value) -> {
                String key = String.valueOf(name);
                if (key.startsWith(CONF_PREFIX)) {
                    settings.put(key.substring(CONF_PREFIX.length()), String.valueOf(value).trim());
                }
            });
        }
        confOverrides.forEach((key, value) -> {
            if (key != null && key.startsWith(CONF_PREFIX)) {
                settings.put(key.substring(CONF_PREFIX.length()), value == null ? null : value.trim());
            }
        });
        return settings;
    }

    private String resolveExclusive(String positional, String optionValue, String name, String optionName) {
        if (positional != null && optionValue != null) {
            throw new IllegalArgumentException(name + " supplied both positionally and via " + optionName);
        }
        return positional != null ? positional : optionValue;
    }

    private void requireValue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private int memorySetting(Map<String, String> settings) {
        String value = firstNonBlank(amMemory, settings.get("amMemory"), "1g");
        return parseSparkMemoryToMb(value);
    }

    /**
     * Parses spark-submit memory strings ({@code 1024m}, {@code 1g}, {@code 512M},
     * bare numbers as MB) into megabytes.
     */
    static int parseSparkMemoryToMb(String value) {
        Matcher matcher = MEMORY_FORMAT.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid memory format: " + value + " (expected e.g. 1g, 2048m)");
        }
        long amount = Long.parseLong(matcher.group(1));
        switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "":  return Math.toIntExact(amount);
            case "b": return Math.toIntExact(Math.max(1, amount / (1024 * 1024)));
            case "k": return Math.toIntExact(Math.max(1, amount / 1024));
            case "m": return Math.toIntExact(amount);
            case "g": return Math.toIntExact(amount * 1024);
            case "t": return Math.toIntExact(amount * 1024 * 1024);
            default:  throw new IllegalArgumentException("invalid memory format: " + value);
        }
    }

    private int integerSetting(Integer cliValue, String confValue, int fallback) {
        String value = cliValue != null ? String.valueOf(cliValue) : confValue;
        return value == null || value.isBlank() ? fallback : Integer.valueOf(value.trim());
    }

    private boolean booleanSetting(String confValue, boolean fallback) {
        return confValue == null || confValue.isBlank() ? fallback : Boolean.parseBoolean(confValue.trim());
    }

    private List<String> listSetting(String cliValue, String confValue) {
        String value = firstNonBlank(cliValue, confValue);
        if (value == null || value.isBlank()) {
            return null;
        }
        return new ArrayList<>(Arrays.stream(value.split(",")).map(String::trim)
                .filter(v -> !v.isEmpty()).collect(java.util.stream.Collectors.toList()));
    }

    private List<String> tagsFrom(Map<String, String> settings) {
        String confTags = settings.get("tags");
        if (confTags == null || confTags.isBlank()) {
            return null;
        }
        return new ArrayList<>(Arrays.stream(confTags.split(",")).map(String::trim)
                .filter(v -> !v.isEmpty()).collect(java.util.stream.Collectors.toList()));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private void validateSubmissionRequest(ClusterSubmitRequest submissionRequest, int maxAttempts) {
        submissionRequest.validate();
        submissionRequest.getYarnSubmission().validate();
        if (maxAttempts == 0 || maxAttempts < -1) {
            throw new IllegalArgumentException("consilens.yarn.maxAppAttempts must be positive or -1 for the cluster default");
        }
    }

    /**
     * Keep Hadoop out of the ordinary CLI runtime. Picocli constructs all child
     * commands while rendering help, so the YARN backend must be loaded only when
     * this command is actually executed.
     */
    private static ClusterSubmitter createDefaultSubmitter() {
        try {
            Class<?> type = Class.forName("com.consilens.cluster.yarn.YarnClusterSubmitter");
            return (ClusterSubmitter) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("YARN client runtime is unavailable; add Hadoop client libraries", e);
        }
    }

    private void closeQuietly(ClusterSubmitter submitter) {
        if (submitter instanceof AutoCloseable) {
            try {
                ((AutoCloseable) submitter).close();
            } catch (Exception ignored) {
                // Submission outcome is already reported; cleanup failure is not fatal.
            }
        }
    }
}
