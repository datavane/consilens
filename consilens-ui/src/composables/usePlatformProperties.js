// ===== 执行平台表单与序列化（YARN / Kubernetes）=====
export function defaultPlatformForm() {
  return {
    platform: "local",
    yarn: {
      archive: "",
      queue: "",
      amMemory: "1g",
      amVCores: 1,
      stagingDir: "",
      files: "",
      jars: "",
      tags: "",
      maxAppAttempts: null,
    },
    kubernetes: {
      image: "",
      namespace: "default",
      jobName: "",
      memory: "1g",
      cpu: "0.5",
      serviceAccount: "",
      imagePullSecrets: "",
      envsText: "",
      secretEnvText: "",
    },
  };
}

export function usePlatformProperties(platformForm) {
  function platformProperties() {
    if (platformForm.platform === "yarn") {
      if (!platformForm.yarn.archive.trim()) {
        throw new Error("请填写 YARN Runtime Archive");
      }
      return omitEmpty({ ...platformForm.yarn });
    }
    if (!platformForm.kubernetes.image.trim()) {
      throw new Error("请填写 Kubernetes 镜像");
    }
    return omitEmpty({
      image: platformForm.kubernetes.image,
      namespace: platformForm.kubernetes.namespace,
      jobName: platformForm.kubernetes.jobName,
      memory: platformForm.kubernetes.memory,
      cpu: platformForm.kubernetes.cpu,
      serviceAccount: platformForm.kubernetes.serviceAccount,
      imagePullSecrets: platformForm.kubernetes.imagePullSecrets,
      envs: parseKeyValueLines(platformForm.kubernetes.envsText, "环境变量"),
      secretEnv: parseSecretEnvLines(platformForm.kubernetes.secretEnvText),
    });
  }

  function restorePlatformProperties(properties) {
    const yarn = properties || {};
    if (platformForm.platform === "yarn") {
      Object.assign(platformForm.yarn, {
        archive: yarn.archive || "",
        queue: yarn.queue || "",
        amMemory: yarn.amMemory || "1g",
        amVCores: yarn.amVCores ?? 1,
        stagingDir: yarn.stagingDir || "",
        files: yarn.files || "",
        jars: yarn.jars || "",
        tags: yarn.tags || "",
        maxAppAttempts: yarn.maxAppAttempts ?? null,
      });
      return;
    }
    const kubernetes = properties || {};
    Object.assign(platformForm.kubernetes, {
      image: kubernetes.image || "",
      namespace: kubernetes.namespace || "default",
      jobName: kubernetes.jobName || "",
      memory: kubernetes.memory || "1g",
      cpu: kubernetes.cpu || "0.5",
      serviceAccount: kubernetes.serviceAccount || "",
      imagePullSecrets: kubernetes.imagePullSecrets || "",
      envsText: Object.entries(kubernetes.envs || {}).map(([name, value]) => `${name}=${value}`).join("\n"),
      secretEnvText: Object.entries(kubernetes.secretEnv || {})
        .map(([name, value]) => `${name}=${value.secretName}/${value.secretKey}`).join("\n"),
    });
  }

  return { platformProperties, restorePlatformProperties };
}

function omitEmpty(values) {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== null
    && value !== undefined && String(value).trim() !== ""));
}

function parseKeyValueLines(text, label) {
  if (!text.trim()) return undefined;
  const result = {};
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const separator = trimmed.indexOf("=");
    if (separator <= 0) {
      throw new Error(`${label}格式应为 KEY=VALUE`);
    }
    result[trimmed.slice(0, separator).trim()] = trimmed.slice(separator + 1).trim();
  }
  return result;
}

function parseSecretEnvLines(text) {
  const references = parseKeyValueLines(text, "Secret 环境变量");
  if (!references) return undefined;
  const result = {};
  Object.entries(references).forEach(([name, value]) => {
    const separator = value.indexOf("/");
    if (separator <= 0 || separator === value.length - 1) {
      throw new Error("Secret 环境变量格式应为 ENV_NAME=secretName/secretKey");
    }
    result[name] = {
      secretName: value.slice(0, separator).trim(),
      secretKey: value.slice(separator + 1).trim(),
    };
  });
  return result;
}
