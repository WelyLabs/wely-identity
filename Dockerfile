# Build stage for custom plugins
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Copy the plugin source
COPY plugins/business-id-mapper /app/plugins/business-id-mapper

# Build the plugin
RUN mvn -f /app/plugins/business-id-mapper/pom.xml clean package -DskipTests

# Final Keycloak image
FROM quay.io/keycloak/keycloak:latest

# Copy custom themes
COPY themes/calendar-app /opt/keycloak/themes/calendar-app

# Copy custom plugins
COPY --from=build /app/plugins/business-id-mapper/target/*.jar /opt/keycloak/providers/business-id-mapper.jar

# Keycloak configuration is handled by environment variables in Kubernetes
# but we can set some defaults here if needed.
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start"]
