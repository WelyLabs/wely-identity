# Build stage for custom plugins
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Copy the plugin source
COPY plugins/business-id-mapper /app/plugins/business-id-mapper

# Build the plugin. Tests run here on purpose: CI has no other Maven step, so this is the
# only place a broken mapper can be stopped before it reaches the login of every user.
RUN mvn -f /app/plugins/business-id-mapper/pom.xml clean package

# Final Keycloak image
FROM quay.io/keycloak/keycloak:latest

# Copy custom themes
COPY themes/calendar-app /opt/keycloak/themes/calendar-app

# Copy custom plugins
COPY --from=build /app/plugins/business-id-mapper/target/*.jar /opt/keycloak/providers/business-id-mapper.jar

# The realm export travels with the image, so there is one copy of it and it is this one.
# It is only read when the container is started with --import-realm, which the local overlay
# does and dev and prod do not: Keycloak skips a realm that already exists, but the flag is
# kept out of the environments whose realm holds real state all the same.
#
# The export carries no credentials. The four accounts that had password hashes were removed
# — a local Keycloak starts with no users, and you register through the application, which is
# also the path that exercises the JIT provisioning this project is built around. The client
# secret is a ${KC_CLIENT_SECRET} placeholder, which Keycloak resolves from the environment at
# import time.
COPY wely-realm.json /opt/keycloak/data/import/wely-realm.json

# Keycloak configuration is handled by environment variables in Kubernetes
# but we can set some defaults here if needed.
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start"]
