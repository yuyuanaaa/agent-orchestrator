# syntax=docker/dockerfile:1

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只复制 pom.xml，利用 Docker 层缓存加速重复构建
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline || true

# 复制源码并打包（spring-boot 插件产出可执行 fat-jar）
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 可执行 jar
COPY --from=build /build/target/agent-orchestrator-0.0.1-SNAPSHOT.jar app.jar

# 上传文件与会话记忆落盘目录（通过卷持久化，重启不丢）
VOLUME ["/app/tmp"]

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
