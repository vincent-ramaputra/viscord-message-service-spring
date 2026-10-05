FROM openjdk:17.0.2-slim AS base

WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

RUN chmod +x mvnw

RUN ./mvnw dependency:go-offline -B

FROM base AS dev

COPY src/ ./src

CMD ["./mvnw", "spring-boot:run"]

FROM base AS build

COPY src/ ./src

RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:17.0.12_7-jre-jammy AS prod

WORKDIR /app

COPY --from=build /app/target/auth-service.jar   .

EXPOSE 8080 9090

CMD ["java", "-jar", "auth-service.jar"]