ARG PROJECT="solana"

FROM eclipse-temurin:23-jdk-alpine AS jlink

WORKDIR /services

COPY . .

ARG PROJECT
RUN --mount=type=secret,id=GITHUB_ACTOR \
    --mount=type=secret,id=GITHUB_TOKEN \
    export GITHUB_ACTOR=$(cat /run/secrets/GITHUB_ACTOR); \
    export GITHUB_TOKEN=$(cat /run/secrets/GITHUB_TOKEN); \
    ./gradlew --console=plain --quiet clean --no-daemon --exclude-task=test :${PROJECT}:jlink -PnoVersionTag=true


FROM alpine:3

ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid "${UID}" \
    sava
USER sava

WORKDIR /sava

ARG PROJECT
COPY --from=jlink /services/${PROJECT}/build/${PROJECT} /sava

ENTRYPOINT [ "./bin/java" ]
