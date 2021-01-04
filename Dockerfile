FROM openjdk:15

ENV BASE_URL="baseUrlPlaceholder"
ENV SHARE_BASE_URL="shareBaseUrlPlaceholder"
ENV SPOTIFY_CLIENT_ID="clientIdPlaceholder"
ENV SPOTIFY_CLIENT_SECRET="secretPlaceholder"
ENV SPOTIFY_REDIRECT_URI_PATH="spotify/callback"

ENV APPLICATION_USER ktor
RUN adduser $APPLICATION_USER

RUN mkdir /app
RUN chown -R $APPLICATION_USER /app

USER $APPLICATION_USER

COPY ./build/libs/pumpkin-0.0.1.jar /app/pumpkin.jar
WORKDIR /app

CMD ["java", "-server", "-XX:+UnlockExperimentalVMOptions", "-XX:InitialRAMFraction=2", "-XX:MinRAMFraction=2", "-XX:MaxRAMFraction=2", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100", "-XX:+UseStringDeduplication", "-jar", "pumpkin.jar"]
