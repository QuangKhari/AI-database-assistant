FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home spring

WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar

USER spring
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]
