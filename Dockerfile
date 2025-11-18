# Stage 1: Build the event listener JAR
FROM maven:3.9-eclipse-temurin-21 AS maven-builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Build Keycloak with the provider
FROM quay.io/keycloak/keycloak:26.4.0 AS keycloak-builder
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true
ENV KC_DB=postgres
COPY --from=maven-builder /build/target/*.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

# Stage 3: Final runtime image
FROM quay.io/keycloak/keycloak:26.4.0
COPY --from=keycloak-builder /opt/keycloak/ /opt/keycloak/
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

# Copy the realm template to /tmp instead of the final location
COPY keycloak-config/eventy-realm-export.json /tmp/realm-template.json

# Create the import directory and adjust permissions
USER root
RUN mkdir -p /opt/keycloak/data/import && \
    chown -R keycloak:keycloak /opt/keycloak/data
USER keycloak

# On startup, substitute variables and start Keycloak
ENTRYPOINT ["/bin/sh", "-c", "sed 's|{{SECRET_ADMIN_PASSWORD}}|'\"$SUPER_ADMIN_PASSWORD\"'|g' /tmp/realm-template.json > /opt/keycloak/data/import/realm.json && /opt/keycloak/bin/kc.sh start-dev --import-realm"]