####
# Stage 1 – build the application
####
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build
COPY pom.xml .
# Download dependencies (cached layer)
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

####
# Stage 2 – runtime image
####
FROM eclipse-temurin:17-jre-alpine

# Non-root user for security
RUN addgroup -S operator && adduser -S operator -G operator
USER operator

WORKDIR /app
COPY --from=builder /build/target/quarkus-app/lib/ /app/lib/
COPY --from=builder /build/target/quarkus-app/*.jar /app/
COPY --from=builder /build/target/quarkus-app/app/ /app/app/
COPY --from=builder /build/target/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8080 8081

ENV JAVA_OPTS="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
