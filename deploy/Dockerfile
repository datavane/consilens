#
# Dockerfile for consilens-server
#
# Runs the Spring Boot based consilens-server using an already-pulled
# JDK 11 image to avoid Docker Hub mirror issues.
#

FROM mcr.microsoft.com/openjdk/jdk:11-ubuntu

# Metadata
LABEL maintainer="consilens"
LABEL description="Consilens Server - Cross-datasource data consistency checker"

# Build argument for the JAR path (override for a release version)
ARG JAR_PATH=consilens-server/target/consilens-server-0.1-SNAPSHOT.jar

# Ensure bash is available (useful for debugging)
RUN apt-get update && apt-get install -y --no-install-recommends wget bash && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /opt/consilens

# Copy the fat JAR under a version-independent name
COPY ${JAR_PATH} consilens-server.jar

# Create artifact storage directory (matches local profile default)
RUN mkdir -p /opt/consilens/.consilens-server/artifacts

# Expose the server port (matches application.yaml)
EXPOSE 16789

# Run the server with 'local' profile (embedded H2, no API key)
ENTRYPOINT ["java", "-jar", "consilens-server.jar", "--spring.profiles.active=local", "--server.port=16789"]
