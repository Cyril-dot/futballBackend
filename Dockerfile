FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn clean package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/speedbet-api-1.0.0.jar app.jar
RUN mkdir -p /app/uploads/crash-cards
EXPOSE 8080
ENTRYPOINT ["java", "-Xms512m", "-Xmx1g", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]