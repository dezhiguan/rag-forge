FROM openjdk:17-slim

WORKDIR /app

RUN mkdir -p /data/files

COPY target/*.jar app.jar

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=docker

ENTRYPOINT ["java", "-jar", "app.jar"]
