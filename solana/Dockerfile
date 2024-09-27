FROM eclipse-temurin:23-jdk-alpine AS jlink

WORKDIR /services

COPY . .
RUN ./gradlew clean --no-daemon --exclude-task=test :solana:jlink -PnoVersionTag=true

FROM alpine:3 AS final

ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid "${UID}" \
    service_user
USER service_user

WORKDIR /sava

COPY --from=jlink /services/solana/build/solana /sava

ENTRYPOINT [ "./bin/java" ]
