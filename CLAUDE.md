# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

智能面试平台后端服务，基于 Spring Boot 4 和 Java 25，使用 Gradle Wrapper 构建。核心功能包括认证、用户管理、简历解析与分析、模拟面试、知识库与 RAG 问答。

## Essential Commands

### Build & Run
```bash
./gradlew compileJava          # 快速编译检查
./gradlew bootRun              # 启动应用（默认端口 8773）
./gradlew build                # 完整构建
./gradlew clean                # 清理构建产物
```

### Testing
```bash
# 运行所有单元测试
./gradlew test

# 运行特定测试类
./gradlew test --tests "com.josh.interviewj.service.AuthServiceTest"

# 运行特定测试方法
./gradlew test --tests "com.josh.interviewj.service.AuthServiceTest.login_Success"

# 运行集成测试
./gradlew integrationTest

# 运行特定集成测试
./gradlew integrationTest --tests "com.josh.interviewj.auth.InviteCodeFlowIntegrationTest"

# 完整验证（单测 + 集成测试 + 覆盖率检查）
./gradlew clean check
```

### Coverage Reports
```bash
# 生成合并的覆盖率报告（单测 + 集成测试）
./gradlew clean test integrationTest jacocoMergedReport

# 验证覆盖率阈值（>= 75%）
./gradlew jacocoMergedCoverageVerification

# 报告位置
# build/reports/jacoco/merged/html/index.html
# build/reports/jacoco/merged/jacocoMergedReport.xml
```

### Recommended Verification Flow
```bash
./gradlew compileJava                                    # 1. 快速编译检查
./gradlew test --tests "<targeted tests>"                # 2. 运行相关单测
./gradlew integrationTest --tests "<targeted tests>"     # 3. 运行相关集成测试
./gradlew clean check                                    # 4. 完整验证
```

## Architecture Overview

### Module Structure

项目按业务领域组织为独立模块，每个模块包含完整的分层结构：

- **auth**: JWT 认证、刷新、登出、黑名单、邀请码
- **user**: 用户资料、语言偏好（locale）
- **resume**: 简历上传、解析（Apache Tika）、分析、JD 匹配
- **interview**: 面试会话、题目生成、答题推进、报告、WebSocket 实时通信
- **knowledgebase**: 文档管理、chunking、embedding、reindex
- **ragqa**: RAG 查询理解、向量检索（pgvector）、答案生成
- **llm**: purpose 路由、provider 配置、模板执行、结构化 JSON 生成
- **chat**: chat session、message timeline、领域事件记录
- **billing**: 计费相关逻辑
- **usage**: 使用量统计与历史记录
- **admin**: 管理后台功能

### Key Design Patterns

1. **分层架构**：Controller → Service → Repository
   - Controller 负责请求验证和响应转换
   - Service 包含业务逻辑和事务管理
   - Repository 处理数据访问

2. **依赖注入**：使用构造器注入 + `final` 字段 + Lombok `@RequiredArgsConstructor`

3. **异常处理**：统一使用 `BusinessException` + `ErrorCode`，错误码保持稳定（如 `AUTH_001`, `USER_003`）

4. **LLM 路由架构**：
   - Purpose-based routing：根据用途（如 `kb_embedding`, `interview_question`）路由到不同 provider/model
   - Template-driven execution：模板位于 `src/main/resources/llm-templates/<provider>/`
   - 运行时配置存储在数据库表：`llm_provider`, `llm_provider_secret`, `llm_model_catalog`, `llm_routing_policy`
   - 模板不存在且 `strict=false` 时才退回 SDK 直连，否则 fail-fast

5. **异步任务**：使用 Spring AMQP + RabbitMQ 处理耗时操作（简历解析、文档 embedding）

6. **向量检索**：使用 pgvector 扩展进行语义相似度搜索

### Database Schema Management

- 使用 Flyway 管理所有 schema 变更
- 迁移文件位于 `src/main/resources/db/migration/`
- 命名格式：`V<version>__<description>.sql`（如 `V10__create_resume_analysis_reports.sql`）
- **禁止直接修改数据库 schema**，所有变更必须通过 Flyway migration

### Configuration Management

- 应用配置：`src/main/resources/application.yml`（不提交敏感信息）
- 示例配置：`src/main/resources/application.example.yml`
- 环境变量：推荐使用 `~/.config/intelligent-interview-backend/non_interactive_env.sh`
- LLM provider 密钥：存储在数据库 `llm_provider_secret` 表，使用 `app.llm.secret` 主密钥加密

### Testing Strategy

- **单元测试**（`src/test/java`）：使用 Mockito 模拟依赖，测试业务逻辑
- **集成测试**（`src/integrationTest/java`）：使用 Testcontainers 启动真实容器（PostgreSQL, Redis, RabbitMQ）
- **测试命名**：使用下划线风格描述场景和结果，如 `login_WrongPassword_ThrowsException`
- **覆盖率要求**：merged line coverage >= 75%
- **覆盖率排除**：Application 类、config 包、DTO/model 类已自动排除

## Code Style & Conventions

### Java Conventions
- 缩进：4 空格
- 类名：`PascalCase`
- 方法/字段：`camelCase`
- 常量：`UPPER_SNAKE_CASE`
- 每个文件一个 public 顶层类

### Dependency Injection
```java
@Service
@RequiredArgsConstructor  // Lombok 生成构造器
public class MyService {
    private final MyRepository repository;  // final 字段，构造器注入
    private final OtherService otherService;
}
```

### Exception Handling
```java
throw new BusinessException(ErrorCode.AUTH_001, "Invalid credentials");
```

### JSON Serialization
- 应用内统一使用 `tools.jackson.*` 包中的 Jackson mapper
- 不要使用其他 JSON 库

## Important Notes

### Security
- **禁止提交敏感信息**：API keys, tokens, passwords, 真实数据库连接信息
- Provider 密钥通过环境变量或数据库加密存储
- JWT 密钥必须通过配置文件或环境变量提供

### LLM Templates
- 模板位置：`src/main/resources/llm-templates/<provider>/`
- 新增 provider 后需要重新构建应用，resources 变更不会自动热生效
- 模板渲染、HTTP 调用、响应抽取任一步失败都会 fail-fast

### Commit Messages
使用 Conventional Commits 格式：
- `feat:` 新功能
- `fix:` Bug 修复
- `docs:` 文档更新
- `chore:` 构建/工具变更

示例：`fix: align usage history with occurred time`

### Pull Requests
PR 应包含：
- 行为变更描述
- 验证命令列表
- 数据库迁移说明（如有）
- 配置变更说明（如有）
- 相关 issue 链接

## Common Pitfalls

1. **不要跳过测试**：所有代码变更都应有对应的测试覆盖
2. **不要直接修改数据库**：使用 Flyway migration
3. **不要硬编码配置**：使用 application.yml 或环境变量
4. **不要忽略覆盖率**：确保 merged coverage >= 75%
5. **不要混用 JSON 库**：统一使用 `tools.jackson.*`
6. **不要在 Service 层捕获所有异常**：让 BusinessException 向上传播到统一异常处理器

## Development Workflow

1. 创建功能分支
2. 编写测试（TDD 推荐）
3. 实现功能
4. 运行 `./gradlew compileJava` 快速检查
5. 运行相关测试 `./gradlew test --tests "<pattern>"`
6. 运行完整验证 `./gradlew clean check`
7. 提交代码（遵循 Conventional Commits）
8. 创建 Pull Request

## Additional Resources

- API 文档：`docs/api/README.md`
- Interview 设计文档：`docs/interview/`
- SQL 脚本示例：`docs/sql/`
- 详细 README：`README.md`
