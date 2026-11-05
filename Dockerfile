FROM gradle:8.14.3-jdk21 AS builder
WORKDIR /workspace
COPY settings.gradle build.gradle ./
COPY src ./src
COPY data/input ./data/input
RUN gradle --no-daemon clean test bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/build/libs/*.jar /app/app.jar
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -Xmx512m -XX:ActiveProcessorCount=2"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
