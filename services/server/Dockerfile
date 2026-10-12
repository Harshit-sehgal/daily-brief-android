# The planning server as a runtime image. The distribution is built outside the image —
# `./gradlew :server:installDist` from the repository root — because the Gradle build also
# configures the Android modules, which have no place in a server image.
#
#   ./gradlew :server:installDist
#   docker build -t dailybrief-server services/server
#   docker run --env-file services/server/.env -p 8080:8080 dailybrief-server
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home dailybrief
COPY build/install/server /opt/server
USER dailybrief
EXPOSE 8080
CMD ["/opt/server/bin/server"]
