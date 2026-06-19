FROM gradle:8.10.2-jdk17 AS build

WORKDIR /workspace
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
COPY examples ./examples
RUN gradle --no-daemon installDist

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/build/install/agentloops /app
COPY --from=build /workspace/examples /app/examples
RUN mkdir -p /data

EXPOSE 8787
ENTRYPOINT ["/app/bin/agentloops"]
CMD ["serve", "--host", "0.0.0.0", "--port", "8787", "--state", "/data/state.json"]
