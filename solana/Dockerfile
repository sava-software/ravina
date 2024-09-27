FROM eclipse-temurin:23-jdk-alpine AS jlink

ARG MAIN_CLASS="software.sava.services.solana.accounts.lookup.http.LookupTableWebService"

WORKDIR /build

COPY . ./
RUN ./gradlew clean -PmainClassName=${MAIN_CLASS} --no-daemon --exclude-task=test :solana:jlink -PnoVersionTag=true

FROM alpine:3 AS final

ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid "${UID}" \
    appuser
USER appuser

COPY --from=jlink /build/solana/build/solana .

EXPOSE 6148

ENTRYPOINT [ "./bin/java" ]
