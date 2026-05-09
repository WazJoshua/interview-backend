FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle gradle.properties ./
COPY src src

RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon bootJar -x test -x integrationTest \
    && cp "$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)" /workspace/app.jar

FROM eclipse-temurin:25-jre

WORKDIR /app

RUN mkdir -p /app/uploads

COPY --from=build /workspace/app.jar /app/app.jar

EXPOSE 8773

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:-} -jar /app/app.jar"]
