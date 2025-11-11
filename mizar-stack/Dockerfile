# syntax=docker/dockerfile:1

# --- build stage (Gradle + JDK 25) ---
FROM gradle:jdk-25-and-25 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle gradle clean bootJar -x test

# --- runtime stage (JRE 25) ---
FROM eclipse-temurin:25-jre
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
