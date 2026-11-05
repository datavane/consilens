# 集群 Comparison Runtime

> 完整的使用文档（参数表、配置键、行为细节、排查方法）见 [docs/06-集群提交指南.md](../../docs/06-集群提交指南.md)。

`consilens-cluster-application` 是 YARN ApplicationMaster 和 Kubernetes Job 的实际入口。它读取 YAML/JSON descriptor，解析运行环境中的 `${env.NAME}`，调用现有 `DefaultCompareRuntime` 执行一次数据比对，并只向 stdout 输出统计摘要。

## 构建

```bash
mvn -pl :consilens-cluster-application -am -DskipTests package
mvn -pl consilens-cli -am -Pyarn-client -DskipTests package
mvn -pl consilens-cli -am -Pkubernetes-client -DskipTests package
```

YARN archive 产物是 `consilens-cluster/consilens-cluster-application/target/consilens-cluster-application-0.1-SNAPSHOT-runtime.zip`。上传至 HDFS 后，YARN 将其解压为 `consilens-runtime/`，启动 classpath 为 `consilens-runtime/*`。

Kubernetes runtime image 使用同一个 shaded jar：

```bash
docker build -f deploy/cluster/Dockerfile -t registry.example/consilens-runtime:0.1.0 .
```

镜像将 jar 放在 `/opt/consilens/runtime/`，与 Kubernetes Job 的固定 classpath 一致。

## 提交

YARN 提交遵循 spark-submit 的使用习惯：descriptor 像应用 jar 一样作为位置参数传入，密钥文件作为第二个位置参数；`--conf` / `--properties-file` 用 `consilens.yarn.*` 键（键名对齐 `spark.yarn.*`：archive、stagingDir、maxAppAttempts、queue、tags、amMemory、amVCores、submit.waitAppCompletion）设置默认值，CLI 选项优先于配置文件。runtime archive 推荐预传 HDFS 一次（等价 `spark.yarn.archive`），日常提交只传 descriptor：

```bash
# 一次性上传 runtime
hdfs dfs -put -f consilens-cluster-application-0.1-SNAPSHOT-runtime.zip /apps/consilens/runtime.zip

# consilens-defaults.conf: consilens.yarn.archive=hdfs:///apps/consilens/runtime.zip 等默认值
java -jar consilens-cli-0.1-SNAPSHOT.jar submit yarn \
  --properties-file consilens-defaults.conf \
  comparison.yaml secrets.properties
```

不加 `--properties-file`/`--conf` 时全部使用与 spark-submit 一致的默认值：staging 在用户 HDFS home 下的 `.consilens/staging/<submissionId>/`（应用成功后自动清理）、queue=default、内存 1g / 1 vcore（`--am-memory` 接受 `1g`/`2048m` 格式）、maxAppAttempts 跟随集群默认。提交后客户端默认等待应用结束并输出 finalStatus 与 tracking URL（`--conf consilens.yarn.submit.waitAppCompletion=false` 可关闭）。

随提交上传本地文件/jar（同 spark-submit 的 `--files`/`--jars`：逗号分隔、支持 `#alias`、远端 URI 不重传；`--files` 在 AM 容器内按文件名可见，`--jars` 追加到 AM classpath 且排在 runtime 之后）：

```bash
java -jar consilens-cli-0.1-SNAPSHOT.jar submit yarn \
  --properties-file consilens-defaults.conf \
  --files ./extra-config.properties#my-config.properties,other.json \
  --jars ./ojdbc8.jar \
  comparison.yaml secrets.properties
```

也可以全部显式指定，本地文件无需配置 staging（自动落到用户 home staging）：

```bash
java -jar consilens-cli-0.1-SNAPSHOT.jar submit yarn \
  --runtime-archive consilens-cluster/consilens-cluster-application/target/consilens-cluster-application-0.1-SNAPSHOT-runtime.zip \
  --am-memory 2g --am-vcores 2 --queue data-quality \
  comparison.yaml secrets.properties
```

远端 URI 同样接受：`--runtime-archive hdfs://namenode/apps/consilens/runtime.zip`，descriptor/secret 也可用远端 URI 代替位置参数。`--runtime-archive` 既接受 runtime.zip（以 `ARCHIVE` 解压后按 `consilens-runtime/*` 启动），也接受单个 fat jar（以 `FILE` 本地化后按 `consilens-runtime.jar` 启动），提交器按扩展名自动选择。提交机的 `HADOOP_CONF_DIR` 会作为 LocalResource 打进 AM 容器（同 Spark 的 `__spark_conf__`），因此能提交 Spark 应用的环境无需任何额外配置即可提交 consilens；YARN 容器需由集群提供 Java 11 的 `JAVA_HOME`（如 `yarn.nodemanager.admin-env`）。

Kubernetes 提交遵循 spark-on-k8s / flink-on-k8s 的应用模型：runtime 打包在 `--image` 内（`/opt/consilens/runtime/`，由 `deploy/cluster/Dockerfile` 构建），没有本地 jar 上传通道；本地 descriptor 像位置参数传入并转为 ConfigMap（同 Flink 用 ConfigMap 承载配置的做法），远端 HTTP(S) URI 直接使用：

```bash
# 构建带 runtime 的镜像
docker build -f deploy/cluster/Dockerfile -t registry.example/consilens-runtime:0.1.0 .

# 提交（descriptor 位置参数，默认等待 Job 结束并输出最终状态）
java -jar consilens-cli-0.1-SNAPSHOT.jar submit kubernetes \
  --image registry.example/consilens-runtime:0.1.0 \
  --memory 1g --cpu 0.5 \
  --env JDBC_DRIVER=org.postgresql.Driver \
  --image-pull-secret registry-credentials \
  comparison.yaml
```

资源参数沿用生态格式：`--memory 1g`（spark 内存格式）、`--cpu 0.5`（核心数）。普通环境变量用 `--env KEY=value`，Secret 引用用 `--secret-env ENVIRONMENT=secret-name/secret-key`（容器内以 secretKeyRef 注入）。默认值可通过 `--properties-file`/`--conf` 以 `consilens.kubernetes.*` 键集中配置（image、namespace、name、memory、cpu、serviceAccount、imagePullPolicy、submit.waitAppCompletion），CLI 选项优先。客户端默认阻塞等待 Job 完成并输出 finalStatus（`consilens.kubernetes.submit.waitAppCompletion=false` 关闭），与 spark-submit 的等待语义一致。Job 使用 `backoffLimit=maxAttempts-1` 表达重试。

`-c/--config` 为兼容已有脚本保留的可选参数；YARN/Kubernetes 提交不会读取它。运行时读取的配置来自 `--descriptor-uri`（远端）或 `--descriptor`（本地 ConfigMap/挂载）指向的 descriptor。
