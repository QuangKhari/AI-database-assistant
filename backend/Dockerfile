# =====================================================================
# STAGE 1 - BUILD
# =====================================================================
FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

RUN chmod +x mvnw \
    && ./mvnw dependency:go-offline -B

COPY src src

RUN ./mvnw clean package -DskipTests -B


# =====================================================================
# STAGE 2 - RUNTIME
# =====================================================================
FROM eclipse-temurin:17-jre-jammy AS runtime

WORKDIR /app

# ---------------------------------------------------------------------
# Tao user non-root
# ---------------------------------------------------------------------
RUN groupadd -r spring \
    && useradd -r \
        -g spring \
        -d /app \
        -s /usr/sbin/nologin \
        spring

# ---------------------------------------------------------------------
# curl cho HEALTHCHECK
# ---------------------------------------------------------------------
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------------
# Copy JAR
# ---------------------------------------------------------------------
COPY --from=build /app/target/*.jar app.jar

# ---------------------------------------------------------------------
# Tao cac thu muc ma application + DuckDB can ghi
#
# /app/data/excel-dbs
#   -> Docker volume excel_data mount vao
#
# /app/.duckdb
#   -> DuckDB temporary working directory
# ---------------------------------------------------------------------
RUN mkdir -p /app/data/excel-dbs /app/.duckdb \
    && chown -R spring:spring /app/data /app/.duckdb \
    && chown spring:spring /app/app.jar

# ---------------------------------------------------------------------
# Chay non-root
# ---------------------------------------------------------------------
USER spring

EXPOSE 8080

# ---------------------------------------------------------------------
# Healthcheck
# ---------------------------------------------------------------------
HEALTHCHECK \
    --interval=15s \
    --timeout=5s \
    --start-period=40s \
    --retries=5 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# ---------------------------------------------------------------------
# JVM options
# ---------------------------------------------------------------------
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]