# Build
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# Tolerate a Windows checkout (CRLF) of the wrapper script.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon -q bootJar -x test

# Run
FROM eclipse-temurin:17-jre
RUN useradd --create-home sync
WORKDIR /app
COPY --from=build /src/build/libs/remixed-dj-sync.jar app.jar
USER sync
ENV PORT=8080 JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
