# Docker 部署示例

`docker-compose.yml` 启动一个 MySQL 8（示例业务库）和 consilens-server：

```bash
cd deploy
cp .env.example .env          # 设置 MYSQL_ROOT_PASSWORD
docker compose up --build -d
docker compose down -v        # 连同数据卷一起清理
```

- server: <http://localhost:16789>，`local` profile（内嵌 H2 保存元数据，免 API Key）
- MySQL: `127.0.0.1:13306`，root 口令来自 `deploy/.env`
- MySQL 初始化脚本位于 `mysql-init/`，仅用于本地演示
- 发布版本可用 `CONSILENS_SERVER_JAR` 覆盖构建使用的 server jar，例如
  `CONSILENS_SERVER_JAR=consilens-server/target/consilens-server-0.1.0.jar`
- 生产部署请使用外部 MySQL/PostgreSQL 元数据库、显式设置
  `CONSILENS_DB_USERNAME` / `CONSILENS_DB_PASSWORD` / `CONSILENS_ENCRYPTION_KEY`，
  并不要使用 `local` profile

`deploy/.env` 已被 `.gitignore` 忽略，不要提交真实口令。
