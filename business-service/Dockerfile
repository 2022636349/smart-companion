# ============================================
# Stage 1: Build — Maven downloads deps & packages jar
# ============================================
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# Pre-resolve deps for better layer caching
RUN mvn -B dependency:go-offline -q || true
COPY src ./src
# NOTE: this pom has no spring-boot-starter-parent and no repackage execution
# binding, so plain `package` yields a thin jar that `java -jar` cannot run.
# Explicitly repackage into an executable fat jar after packaging.
RUN mvn -B -DskipTests package spring-boot:repackage -q
# Verify the fat jar exists and actually bundles dependencies (>10MB)
RUN test -n "$(find target -maxdepth 1 -name '*.jar' ! -name '*.original' -size +10M)" \
    && echo "Fat JAR OK"

# ============================================
# Stage 2: Runtime — slim JRE, curl for healthcheck
# ============================================
FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre-noble
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
ENV TZ=Asia/Shanghai
ENV JAVA_OPTS=""

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
