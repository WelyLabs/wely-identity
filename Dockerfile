# Build stage for custom plugins
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Copy the plugin source
COPY plugins/business-id-mapper /app/plugins/business-id-mapper

# Build the plugin. Tests run here on purpose: CI has no other Maven step, so this is the
# only place a broken mapper can be stopped before it reaches the login of every user.
RUN mvn -f /app/plugins/business-id-mapper/pom.xml clean package

# Final Keycloak image
# Pinned: `latest` changed Keycloak under every build, and a release started refusing a realm export
# not named <realm>-realm.json. Bump it on purpose, and check the import in the local overlay.
FROM quay.io/keycloak/keycloak:26.8.0

# Copy custom themes
COPY themes/calendar-app /opt/keycloak/themes/calendar-app
# The master realm's login screen, in front of the admin console: extends calendar-app.
COPY themes/wely-admin /opt/keycloak/themes/wely-admin

# Copy custom plugins
COPY --from=build /app/plugins/business-id-mapper/target/*.jar /opt/keycloak/providers/business-id-mapper.jar

# The realm export travels with the image, so there is one copy of it and it is this one.
# It is only read when the container is started with --import-realm, which the local overlay
# does and dev and prod do not: Keycloak skips a realm that already exists, but the flag is
# kept out of the environments whose realm holds real state all the same.
#
# No realm export is baked in. The only one lives in wely-gitops-infra, under
# overlays/local/keycloak/, and the local overlay mounts it over /opt/keycloak/data/import --
# which hid whatever this image carried. dev and prod never import at all: their realm holds
# state, and the realm-settings hook keeps it in shape.

# Keycloak configuration is handled by environment variables in Kubernetes
# but we can set some defaults here if needed.
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start"]
