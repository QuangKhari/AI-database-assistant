# =====================================================================
# STAGE 1 - BUILD
# Dùng image có Maven + JDK 17 để build ra file .jar. Image này KHÔNG
# được đưa vào image cuối cùng, nên toolchain build (Maven, source code,
# .m2 cache) không làm phình image production.
# =====================================================================
FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /app

# Copy trước mvnw + pom.xml để tận dụng Docker layer cache: nếu chỉ sửa
# code (không đổi dependency), bước "download dependency" sẽ không phải
# chạy lại mỗi lần build.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code va build. Bo qua test khi build image (test da chay
# rieng o buoc CI truoc khi build image, xem huong dan ben duoi).
COPY src src
RUN ./mvnw clean package -DskipTests -B

# =====================================================================
# STAGE 2 - RUNTIME
# Chi chua JRE (khong can JDK/Maven) + file .jar da build => image nho
# gon hon nhieu va giam attack surface.
# =====================================================================
FROM eclipse-temurin:17-jre-jammy AS runtime

WORKDIR /app

# Khong chay app bang root: tao user/group rieng "spring" khong co
# quyen dang nhap shell, khong co home directory can thiet.
RUN groupadd -r spring && useradd -r -g spring -d /app -s /usr/sbin/nologin spring

# curl dung cho HEALTHCHECK ben duoi (goi actuator/health tu chinh
# container). Cai xong xoa apt cache de giu image nho.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

# Doi quyen so huu file jar cho user "spring" TRUOC khi chuyen USER,
# neu khong container se khong doc duoc file khi chay o quyen non-root.
RUN chown spring:spring app.jar

USER spring

EXPOSE 8080

# Healthcheck goi endpoint /actuator/health (yeu cau da bat Spring Boot
# Actuator - xem huong dan phia duoi). Docker/Compose se dua container
# vao trang thai "unhealthy" neu backend khong phan hoi, giup phat hien
# som container "chay nhung khong hoat dong" (vi du crash loop, DB chua
# san sang, app treo khi khoi dong).
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# KHONG hard-code bat ky secret/API key nao o day. Toan bo config
# (DB_PASSWORD, JWT_SECRET, ENCRYPTION_SECRET, GEMINI_API_KEY, ...)
# duoc truyen vao luc "docker run"/"docker compose up" qua environment
# variables - xem docker-compose.yml va .env.example.
#
# JAVA_OPTS de trong mac dinh, co the override luc chay container de
# gioi han heap (vi du -Xmx512m) ma khong can build lai image.
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]