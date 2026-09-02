# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/gateway-app/target/gateway-app.jar app.jar
ENV SPRING_PROFILES_ACTIVE=cloud
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
