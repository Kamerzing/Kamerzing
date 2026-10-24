FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 payment && \
    useradd --system --uid 10001 --gid payment --no-create-home payment
WORKDIR /app
COPY --chown=payment:payment build/libs/payment-core-0.1.0.jar /app/app.jar
USER payment
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
