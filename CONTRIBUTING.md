# Contributing to Consilens

感谢你对 Consilens 的关注！我们欢迎各种形式的贡献。

## 开发环境

- **JDK**: 11+
- **构建工具**: Maven 3.6+
- **IDE**: IntelliJ IDEA（推荐）或 Eclipse

## 快速开始

```bash
# 克隆仓库
git clone https://github.com/NoeticLens/consilens.git
cd consilens

# 构建项目
mvn clean package -DskipTests

# 运行测试
mvn test
```

## 项目结构

- `consilens-cli`：CLI 入口，配置解析与执行编排
- `consilens-core`：比对规划（DefaultComparePlanner）、算法（ChecksumDiffer / JoinDiffer）、差异模型
- `consilens-connector`：连接器抽象（ConnectorProvider / DatasetHandle / CapabilitySet）、数据库方言插件
- `consilens-sink`：输出 SPI（console / json / csv / table）
- `consilens-spi`：通用插件加载运行时
- `consilens-dist`：发行包组装

核心设计原则：规划器（`DefaultComparePlanner`）只基于能力集合（`CapabilitySet`）选择执行计划，不区分数据源类型。

## 代码规范

- Java 11 基线，四空格缩进
- 类名 `UpperCamelCase`，方法/字段 `lowerCamelCase`，常量 `SCREAMING_SNAKE_CASE`
- 使用 Lombok 减少样板代码（`@Getter`, `@Slf4j`, `@Builder` 等）
- 导入排序：常规导入在前，静态导入在后
- 提交前运行 IDE 格式化（Google 或 IntelliJ 默认配置）

## 提交规范

遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

- `feat:` 新功能
- `fix:` 修复 Bug
- `refactor:` 代码重构
- `docs:` 文档更新
- `test:` 测试相关
- `chore:` 构建/工具链

示例：
```
feat: add Oracle connector support
fix: resolve date comparison issue in ChecksumDiffer
```

## 测试要求

- 单元测试使用 JUnit 5 + AssertJ + Mockito
- 集成测试文件命名为 `*IT.java`，使用 Testcontainers
- 修复 Bug 或新增功能时必须包含对应测试
- 提交前确保 `mvn test` 全部通过

## Pull Request 流程

1. Fork 仓库并创建特性分支（`feat/xxx` 或 `fix/xxx`）
2. 编写代码和测试
3. 确保 CI 通过（`mvn clean test`）
4. 提交 PR 并填写描述模板
5. 等待 Code Review

## 安全注意事项

- **永远不要**提交数据库凭据或密钥
- 使用环境变量或 `examples/` 中的占位符
- 日志中不要包含客户数据

## 问题反馈

- Bug 报告请使用 [Bug Report](.github/ISSUE_TEMPLATE/bug_report.md) 模板
- 功能建议请使用 [Feature Request](.github/ISSUE_TEMPLATE/feature_request.md) 模板
