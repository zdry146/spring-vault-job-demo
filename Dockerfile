# syntax=docker/dockerfile:1
# ----------------------------------------------------------------------------
# spring-vault-job-demo — minimal runtime image
#
# Build pre-step (host):
#     mvn -B -DskipTests package      -> target/spring-vault-job-demo-*.jar
#
# Build image:
#     docker build -t spring-vault-job-demo:0.1.0 .
#
# We skip the in-image Maven build because the JAR is already produced by
# `mvn package` on the host and copying a single fat jar is ~10× faster
# than re-running the toolchain inside the build.
# ----------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-jammy
LABEL org.opencontainers.image.title="spring-vault-job-demo" \
      org.opencontainers.image.description="Spring Boot CLI job that fetches dynamic DB credentials from HashiCorp Vault" \
      org.opencontainers.image.source="https://github.com/zdry146/spring-vault-job-demo"

WORKDIR /app

# Non-root user
RUN groupadd --system --gid 1001 vaultjob \
 && useradd  --system --uid 1001 --gid vaultjob --create-home --shell /usr/sbin/nologin vaultjob

# Single fat-jar payload (Spring Boot repackage produces a runnable jar at this path)
COPY target/spring-vault-job-demo-*.jar /app/app.jar

USER vaultjob

ENTRYPOINT ["java", "-jar", "/app/app.jar"]