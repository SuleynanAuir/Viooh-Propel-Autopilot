FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN groupadd --system propel && useradd --system --gid propel --home-dir /app propel
WORKDIR /app
COPY --from=build /build/target/Auto_project-1.0-SNAPSHOT.jar /app/propel.jar
ENV PORT=8080 \
    PROPEL_MAX_UPLOAD_BYTES=262144000 \
    PROPEL_MAX_CONCURRENT_EXPORTS=1 \
    PROPEL_ALLOW_REMOTE_IMAGES=true \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"
EXPOSE 8080
USER propel
CMD ["java", "-cp", "/app/propel.jar", "com.autoproject.web.WebMain"]
