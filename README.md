# 智能面试平台后端

基于 Spring Boot 4 和 Java 25 的智能面试平台后端服务，当前仓库已经覆盖认证、用户、简历、模拟面试、知识库与 RAG 问答等核心能力，并使用 PostgreSQL、Redis、RabbitMQ、Flyway 与多 provider LLM 路由支撑运行时链路。

## 当前状态

- 已实现 JWT 认证、刷新、登出与黑名单失效机制
- 已实现用户资料与 `locale` 内容语言偏好管理
- 已实现简历上传、解析、分析、JD 匹配与相关异步任务
- 已实现 Interview Phase 1 文本面试主流程
- 已实现面试问题生成、答题评估与报告生成的 LLM 支撑能力
- 已实现知识库文档管理、嵌入、RAG 问答与 chat session/timeline 读取
- 已具备按 purpose 路由的 LLM 基础设施，以及模板驱动 provider 适配能力

## 技术栈

- 框架: Spring Boot `4.0.2`
- 语言: Java `25`
- 构建: Gradle Wrapper
- 数据库: PostgreSQL + Flyway
- 缓存/消息: Redis + RabbitMQ
- 向量检索: pgvector
- 文档解析: Apache Tika
- 认证: Spring Security + JWT
- LLM: `openai-java` SDK + 自定义路由/模板执行层
- 测试: JUnit 5、Mockito、Spring Test、Testcontainers

## 模块概览

- `auth`: 注册、登录、刷新、登出、账户安全
- `user`: 用户资料与语言偏好
- `resume`: 简历上传、解析、分析、JD 匹配
- `interview`: 面试会话、题目、答题推进、报告、timeline、WebSocket 事件
- `knowledgebase`: 知识库、文档摄入、chunking、embedding、reindex
- `ragqa`: RAG 查询理解、检索、答案生成
- `llm`: purpose 路由、provider 配置、模板执行、结构化 JSON 生成
- `chat`: chat session、message timeline 与领域事件记录

## 环境要求

- JDK `25`
- PostgreSQL
- Redis
- RabbitMQ

本项目默认通过 Gradle Wrapper 构建，不要求系统全局安装 Gradle。

## 快速开始

### 1. 准备基础依赖

创建 PostgreSQL 数据库，例如：

```sql
CREATE DATABASE interview_db;
CREATE USER postgres WITH PASSWORD 'password';
GRANT ALL PRIVILEGES ON DATABASE interview_db TO postgres;
```

启动 Redis：

```bash
redis-server
```

启动 RabbitMQ。使用 Docker 时可直接参考仓库根目录的 `compose.yml`，其中包含 PostgreSQL、Redis、RabbitMQ 和 backend 服务。

### 2. 配置应用

优先参考 [src/main/resources/application.example.yml](src/main/resources/application.example.yml) 准备本地配置。默认端口为 `8773`。

最小本地配置通常包括：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/interview_db
    username: postgres
    password: your_password
```

Interview timeout cleanup 默认开启，相关配置位于 `app.interview.timeout.*`，单位均为毫秒：

- `enabled`: 是否启用启动补扫和定时超时回收
- `poll-interval`: 定时扫描间隔
- `idle-threshold`: 超时判定阈值
- `batch-size`: 单轮最多处理的 session 数

本地验证时可以临时把 `idle-threshold` 调小到例如 `60000`，把 `poll-interval` 调小到例如 `5000`，方便快速观察超时 abort 行为。

### 3. 配置非交互环境变量

仓库约定不要把 provider 密钥写入仓库文件。推荐把环境变量放到用户本地文件：

```bash
~/.config/intelligent-interview-backend/non_interactive_env.sh
```

再从 `~/.profile` 中按需 `source` 该文件。

当前仓库环境下，知识库摄入相关链路通常依赖这些变量：

- `NV_API`
- `ALI_API`
- `RAG_API`

`provider` / `model` / `routing` / `rerank` 的运行时真值以数据库 AI resources 为准。本地文件只负责基础环境项，例如数据库连接、JWT、以及 `app.llm.secret` 主密钥。

### 4. 构建与运行

```bash
./gradlew build
./gradlew bootRun
```

应用默认启动在：

```text
http://localhost:8773
```

## 常用命令

```bash
./gradlew build
./gradlew test
./gradlew integrationTest
./gradlew check
./gradlew jacocoMergedReport
./gradlew jacocoMergedCoverageVerification
./gradlew clean
./gradlew bootRun
./gradlew tasks --all
```

单测与集成测试：

```bash
./gradlew test --tests "com.josh.interviewj.service.AuthServiceTest"
./gradlew test --tests "com.josh.interviewj.service.AuthServiceTest.login_Success"
./gradlew integrationTest
```

Coverage：

```bash
./gradlew clean test integrationTest jacocoMergedReport
./gradlew clean check
./gradlew jacocoMergedCoverageVerification
```

本地 merged coverage 报告输出：

```text
build/reports/jacoco/merged/html/index.html
build/reports/jacoco/merged/jacocoMergedReport.xml
```

推荐本地验证顺序：

```bash
./gradlew compileJava
./gradlew test --tests "<targeted tests>"
./gradlew integrationTest --tests "<targeted integration tests>"
./gradlew clean check
```

## LLM 配置说明

运行时 AI 资源真值位于数据库：

- `llm_provider`
- `llm_provider_secret`
- `llm_model_catalog`
- `llm_routing_policy`

仓库内配置文件只保留这些用途：

- `app.llm.secret`: provider secret 加解密主密钥
- `application.example.yml`: 演示字段结构与本地最小运行所需环境项

初始化 `kb_query_rerank` 可直接使用：

- [docs/sql/2026-04-08-seed-kb-query-rerank.sql](docs/sql/2026-04-08-seed-kb-query-rerank.sql)

如需生成 `llm_provider_secret` 的密文字段，可使用：

- [src/test/java/com/josh/interviewj/llm/support/LlmProviderSecretEncryptorMain.java](src/test/java/com/josh/interviewj/llm/support/LlmProviderSecretEncryptorMain.java)

模板目录固定在：

```text
src/main/resources/llm-templates/<provider>
```

当前执行规则：

- 只有“模板不存在且 `strict=false`”时才允许退回 SDK 直连
- 一旦模板命中，模板渲染、HTTP 调用、响应抽取等任一步失败都直接 fail-fast
- 新增 provider 后，需要重新构建并发布应用，`resources` 变更不会自动热生效

## 项目结构

```text
src/
├── main/
│   ├── java/com/josh/interviewj/
│   │   ├── admin/
│   │   ├── auth/
│   │   ├── billing/
│   │   ├── chat/
│   │   ├── common/
│   │   ├── config/
│   │   ├── interview/
│   │   ├── knowledgebase/
│   │   ├── llm/
│   │   ├── ragqa/
│   │   ├── resume/
│   │   ├── security/
│   │   ├── usage/
│   │   └── user/
│   └── resources/
│       ├── application.example.yml
│       ├── application.yml
│       ├── db/migration/
│       └── llm-templates/
├── test/
└── integrationTest/
```

## 部署

示例 Docker 用法：

```bash
docker build -t intelligent-interview-backend .

docker run \
  --env-file ./backend.env \
  -v "$(pwd)/uploads:/app/uploads" \
  -p 8773:8773 \
  intelligent-interview-backend
```

生产环境至少应配置：

- 数据库连接
- Redis 连接
- RabbitMQ 连接
- JWT 密钥
- `app.llm.secret`
- 数据库中的 LLM provider、model、routing 与 secret 记录
- 上传目录挂载
- `config/application-prod.yml`

## 开发约定

- 始终使用 `./gradlew`
- 代码风格以仓库现有实现为准
- 应用内 JSON mapper 使用 `tools.jackson.*`
- 不要把真实密钥提交到仓库
- schema 变更通过 Flyway migration 管理

## 许可证

MIT License
