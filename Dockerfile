# Build stage
FROM maven:3.9-eclipse-temurin-8 AS build

WORKDIR /build

# Copy and install the Gitblit JAR (not available in public Maven repos)
# We create a minimal POM to avoid transitive dependency resolution issues
COPY lib/* /tmp/
RUN mvn install:install-file -Dfile=/tmp/gitblit-1.10.0.jar -DpomFile=/tmp/gitblit-1.10.0.pom -q

# Copy pom.xml and source, then build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Final stage - minimal image with just the plugin
FROM busybox:latest

COPY --from=build /build/target/mcp-support-plugin-*.zip /plugins/

# Keep container alive if run directly (optional, for debugging)
CMD ["sh", "-c", "echo 'Plugin available at /plugins'; ls -la /plugins; sleep infinity"]
