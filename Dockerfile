# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS base
WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew
COPY src src
# The published API contract is a test input: BalanceResponseContractTest reads balances-api.yaml and
# fails if the code and the contract drift apart. Without it in the image the containerized `check`
# has six tests it cannot run — and a gate that silently loses a test is worse than no gate. Only the
# contracts directory is copied; the rest of specs/ is documentation the build does not read.
COPY specs/001-core-banking-balance/contracts specs/001-core-banking-balance/contracts

FROM base AS test
RUN --mount=type=cache,target=/root/.gradle ./gradlew check --no-daemon

FROM base AS builder
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon \
    && cp $(ls build/libs/*.jar | grep -v plain) /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=builder /workspace/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
