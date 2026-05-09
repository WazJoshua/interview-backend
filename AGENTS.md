# Repository Guidelines

## Project Structure & Module Organization

This repository is a Spring Boot 4 backend using Java 25 and Gradle Wrapper. Application code lives in `src/main/java/com/josh/interviewj`, grouped by domain modules such as `admin`, `auth`, `billing`, `knowledgebase`, `llm`, `ragqa`, `resume`, `usage`, and `user`.

Resources live in `src/main/resources`. Flyway migrations are in `src/main/resources/db/migration`, and LLM prompt templates are in `src/main/resources/llm-templates`. Unit tests are under `src/test/java`; Spring or container-backed integration tests are under `src/integrationTest/java`.

## Build, Test, and Development Commands

Use the Gradle Wrapper for all local and CI work:

- `./gradlew compileJava` compiles main sources quickly.
- `./gradlew bootRun` starts the backend locally.
- `./gradlew test` runs unit tests.
- `./gradlew integrationTest` runs integration tests.
- `./gradlew clean check` runs the full verification path used by CI.
- `./gradlew jacocoMergedReport` generates merged unit and integration coverage reports.

Run one test with `./gradlew test --tests "com.josh.interviewj.service.AuthServiceTest"` or `./gradlew integrationTest --tests "com.josh.interviewj.auth.InviteCodeFlowIntegrationTest"`.

## Coding Style & Naming Conventions

Use 4 spaces for Java indentation and keep one public top-level class per file. Prefer constructor injection with `final` fields and Lombok `@RequiredArgsConstructor`. Keep controllers thin; put business rules in services and queries in repositories.

Use `PascalCase` for classes, `camelCase` for methods and fields, and `UPPER_SNAKE_CASE` for constants. Business error codes should stay stable, for example `AUTH_001` or `USER_003`.

## Testing Guidelines

Tests use JUnit 5, Mockito, Spring Test, and Testcontainers. Name tests with a clear scenario and result, often using underscore style such as `login_WrongPassword_ThrowsException`. Add regression coverage for bug fixes, including success and failure paths.

Merged JaCoCo line coverage must remain at or above `0.75`. The HTML report is written to `build/reports/jacoco/merged/html/index.html`.

## Commit & Pull Request Guidelines

Recent commits use short Conventional Commit prefixes: `feat:`, `fix:`, `docs:`, and `chore:`. Keep the subject imperative and specific, for example `fix: align usage history with occurred time`.

Pull requests should describe the behavior change, list verification commands run, mention database migrations or config changes, and link any related issue.

## Security & Configuration Tips

Do not commit secrets, provider keys, tokens, or local credential files. Keep environment-specific values outside the repository, and use local shell profile files for provider variables such as `NV_API`, `ALI_API`, and `RAG_API`. Database schema changes must use Flyway migrations.
