FROM openjdk:17-slim

WORKDIR /app

RUN mkdir -p /data/files

COPY target/rag-forge-*.jar app.jar

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", "-Xms512m", "-Xmx1536m", "-jar", "app.jar"]
