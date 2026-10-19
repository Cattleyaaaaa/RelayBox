FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S relaybox && adduser -S relaybox -G relaybox
COPY --from=build /workspace/target/relaybox-delivery-service-*.jar /app/relaybox-delivery-service.jar
USER relaybox
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/relaybox-delivery-service.jar"]
