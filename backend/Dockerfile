FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn -B -e -C -T 1C org.apache.maven.plugins:maven-dependency-plugin:3.6.1:go-offline
COPY src ./src
RUN mvn -B -e -T 1C clean package -DskipTests

FROM amazoncorretto:17-alpine-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata curl
COPY --from=builder /app/target/*.jar /app/app.jar
EXPOSE 8080 9090
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/api/v1/cluster/status || exit 1
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
