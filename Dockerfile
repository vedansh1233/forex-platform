# Production image for the Spring Boot API (used by Railway; works with any Docker host).

# --- Build ---
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
# Dependencies first, so they are cached between builds that only change source code
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run locally and in CI; the image build only packages
RUN mvn -B -q package -DskipTests

# --- Run ---
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 spring
COPY --from=build /app/target/forex-platform-*.jar app.jar
USER spring
# Size the heap from the container's memory limit; restart cleanly instead of limping on OOM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
# server.port reads $PORT (set by Railway), defaulting to 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
