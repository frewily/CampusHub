FROM eclipse-temurin:8-jre-jammy

WORKDIR /app
COPY --chown=10001:10001 target/campushub-0.0.1-SNAPSHOT.jar /app/application.jar
RUN mkdir -p /app/data/uploads \
    && chown -R 10001:10001 /app

ENV SPRING_PROFILES_ACTIVE=prod
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
