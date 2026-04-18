FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline -DskipTests

COPY src ./src
RUN mvn -B package -DskipTests \
    && mvn -q dependency:copy-dependencies -DoutputDirectory=target/dependency -DincludeScope=runtime \
    && cp target/distrisync-*.jar target/server.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

EXPOSE 9090/tcp
EXPOSE 9090/udp

COPY --from=build /app/target/server.jar /app/server.jar
COPY --from=build /app/target/dependency /app/lib

ENTRYPOINT ["java", "-cp", "/app/server.jar:/app/lib/*", "com.distrisync.server.WhiteboardServer", "9090", "/app/distrisync-data"]
