# Dockerfile — how to package the counter server as a container image.
#
# This is a single-stage Dockerfile: we expect the JAR to already be built on
# the host (`mvn -q -DskipTests package`) and we just wrap it in an image with
# a JRE. A multi-stage Dockerfile could build Maven inside, but that's extra
# complexity we don't need for a demo.

FROM eclipse-temurin:17-jre

WORKDIR /app

# The JAR we built on the host. Copied into the image at build time — the image
# is immutable once built. Every container spawned from it has this exact JAR.
COPY target/challenge-11-counter-1.0-SNAPSHOT.jar app.jar

# Same config for every container. Per-container differences come via env vars
# (see docker-compose.yml INSTANCE_NAME, POSTGRES_* vars) and Dropwizard's
# ${ENV:-default} syntax.
COPY config.yml config.yml

# Declare the ports the JVM binds inside the container. This is documentation
# for readers + tooling; it doesn't actually expose the ports to the host —
# that happens via `ports:` in docker-compose.yml.
EXPOSE 8080 8081

# No /app/shared-data directory anymore — the DB is a separate service reached
# over TCP in challenge 10, not a file this container holds.

ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["server", "config.yml"]
