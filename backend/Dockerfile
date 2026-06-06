FROM eclipse-temurin:17-jre

WORKDIR /app

RUN mkdir -p /data/files

COPY target/rag-forge-*.jar app.jar

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_OPTS="-Xms512m -Xmx2g"

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]
