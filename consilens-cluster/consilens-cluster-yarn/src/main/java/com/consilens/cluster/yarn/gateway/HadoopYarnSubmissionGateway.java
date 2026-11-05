package com.consilens.cluster.yarn.gateway;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.URL;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.exceptions.YarnException;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/**
 * Production YARN gateway backed by a real {@link YarnClient}. The client reads
 * {@code yarn-site.xml}/{@code core-site.xml} from the classpath like Spark/Flink
 * client utilities do; no shell invocation or hand-built REST submission is used.
 */
public class HadoopYarnSubmissionGateway implements YarnSubmissionGateway {

    private final Configuration configuration;
    private final YarnClient yarnClient;

    public HadoopYarnSubmissionGateway(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.yarnClient = YarnClient.createYarnClient();
        this.yarnClient.init(configuration);
        this.yarnClient.start();
    }

    @Override
    public ApplicationSubmissionContext createSubmissionContext() {
        try {
            return yarnClient.createApplication().getApplicationSubmissionContext();
        } catch (YarnException | IOException e) {
            throw new IllegalStateException("Unable to create YARN application", e);
        }
    }

    @Override
    public LocalResource createLocalResource(URI resourceUri, LocalResourceType type) {
        try {
            Path path = new Path(resourceUri);
            FileSystem fileSystem = path.getFileSystem(configuration);
            FileStatus status = fileSystem.getFileStatus(path);
            URL resourceUrl = URL.fromURI(resourceUri);
            return LocalResource.newInstance(resourceUrl, type, LocalResourceVisibility.APPLICATION,
                    status.getLen(), status.getModificationTime());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to resolve YARN local resource " + resourceUri, e);
        }
    }

    @Override
    public URI stageLocalFile(URI localUri, URI stagingBase) {
        try {
            Path source = new Path(localUri);
            FileSystem localFileSystem = FileSystem.getLocal(configuration);
            FileStatus status = localFileSystem.getFileStatus(source);
            if (status.isDir()) {
                throw new IllegalStateException("local artifact must be a file: " + localUri);
            }
            String fileName = source.getName();
            Path stagingDestination = new Path(directoryUri(stagingBase).resolve(fileName));
            FileSystem remoteFileSystem = stagingDestination.getFileSystem(configuration);
            FileUtil.copy(localFileSystem, source, remoteFileSystem, stagingDestination, false, configuration);
            return stagingDestination.toUri();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to stage local artifact " + localUri, e);
        }
    }

    @Override
    public void deleteStagingDirectory(URI stagingUri) {
        if (stagingUri == null) {
            return;
        }
        try {
            Path path = new Path(stagingUri);
            path.getFileSystem(configuration).delete(path, true);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to delete YARN staging directory " + stagingUri, e);
        }
    }

    private URI directoryUri(URI stagingBase) {
        String value = stagingBase.toString();
        return value.endsWith("/") ? stagingBase : URI.create(value + "/");
    }

    @Override
    public ApplicationId submit(ApplicationSubmissionContext context) {
        try {
            return yarnClient.submitApplication(context);
        } catch (YarnException | IOException e) {
            throw new IllegalStateException("YARN application submission failed", e);
        }
    }

    @Override
    public String defaultStagingBase() {
        try {
            return FileSystem.get(configuration).getHomeDirectory() + "/.consilens/staging";
        } catch (IOException e) {
            throw new IllegalStateException("Unable to resolve the submitting user's HDFS home directory", e);
        }
    }

    @Override
    public java.util.Optional<String> applicationState(String applicationId) {
        return report(applicationId, report -> report.getYarnApplicationState().toString());
    }

    @Override
    public java.util.Optional<String> applicationFinalStatus(String applicationId) {
        return report(applicationId, report -> report.getFinalApplicationStatus().toString());
    }

    @Override
    public java.util.Optional<String> trackingUrl(String applicationId) {
        return report(applicationId, ApplicationReport::getTrackingUrl);
    }

    @Override
    public java.util.Optional<String> applicationDiagnostics(String applicationId) {
        return report(applicationId, ApplicationReport::getDiagnostics);
    }

    private java.util.Optional<String> report(String applicationId,
                                              java.util.function.Function<ApplicationReport, String> extractor) {
        try {
            ApplicationReport applicationReport = yarnClient.getApplicationReport(ApplicationId.fromString(applicationId));
            return java.util.Optional.ofNullable(extractor.apply(applicationReport));
        } catch (YarnException | IOException | IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    @Override
    public int killByTag(String tag) {
        int killed = 0;
        try {
            // 只拉取可能被 kill 的活跃状态，客户端本地过滤 tags——避免依赖
            // yarn.resourcemanager.application-tags.enabled 与其索引时序。
            for (ApplicationReport report : yarnClient.getApplications(
                    java.util.EnumSet.of(org.apache.hadoop.yarn.api.records.YarnApplicationState.NEW,
                            org.apache.hadoop.yarn.api.records.YarnApplicationState.NEW_SAVING,
                            org.apache.hadoop.yarn.api.records.YarnApplicationState.SUBMITTED,
                            org.apache.hadoop.yarn.api.records.YarnApplicationState.ACCEPTED,
                            org.apache.hadoop.yarn.api.records.YarnApplicationState.RUNNING))) {
                if (report.getApplicationTags() == null || !report.getApplicationTags().contains(tag)) {
                    continue;
                }
                try {
                    yarnClient.killApplication(report.getApplicationId());
                    killed++;
                } catch (YarnException | IOException e) {
                    // Individual kill failures must not abort the cleanup sweep.
                }
            }
        } catch (YarnException | IOException e) {
            // Sweep is best effort; the subsequent submission validates health.
        }
        return killed;
    }

    @Override
    public void kill(String applicationId) {
        try {
            yarnClient.killApplication(ApplicationId.fromString(applicationId));
        } catch (YarnException | IOException | IllegalArgumentException e) {
            // Kill is best effort: the application may already be terminal.
        }
    }

    @Override
    public void close() {
        try {
            yarnClient.close();
        } catch (IOException e) {
            // The application was already submitted to the ResourceManager; a failed
            // client shutdown must not turn a successful submission into an error.
        }
    }
}
