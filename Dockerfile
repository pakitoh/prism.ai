# syntax=docker/dockerfile:1

# ---- Build stage: compile the Maven reactor and repackage the Spring Boot fat jar ----
# Java 25 (maven.compiler.release=25). Swap the tag if your registry publishes a
# different Maven+temurin-25 coordinate.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# 1) Copy only the POMs first and resolve dependencies, so this layer is cached
#    and re-downloads only when a POM changes (not on every source edit).
COPY pom.xml ./
COPY prism-domain/pom.xml prism-domain/
COPY prism-adapters-out/pom.xml prism-adapters-out/
COPY prism-adapters-in/pom.xml prism-adapters-in/
COPY prism-boot/pom.xml prism-boot/
RUN mvn -B -q dependency:go-offline

# 2) Copy sources and build. Tests run in CI; skip them here for a fast, hermetic image build.
COPY prism-domain/src prism-domain/src
COPY prism-adapters-out/src prism-adapters-out/src
COPY prism-adapters-in/src prism-adapters-in/src
COPY prism-boot/src prism-boot/src
# .git lets the git-commit-id plugin stamp the commit into git.properties. Copied last so it
# only invalidates the build (not the dependency) layer; the plugin uses JGit, no git binary.
COPY .git .git
RUN mvn -B -q -DskipTests clean package

# ---- Runtime stage: slim JRE running the repackaged jar ----
# If 25-jre is not published in your registry, use eclipse-temurin:25-jdk.
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Run as an unprivileged user.
RUN groupadd --system prism && useradd --system --gid prism --home-dir /app prism

# Only the boot module is executable; '*' avoids hardcoding the version.
COPY --from=build /build/prism-boot/target/prism-boot-*.jar app.jar
USER prism

EXPOSE 8087

# JVM is container-aware by default; extra flags can be passed via JAVA_OPTS.
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
