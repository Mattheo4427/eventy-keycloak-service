# Stage 1: Build the event listener JAR
FROM maven:3.9-eclipse-temurin-21 AS maven-builder

WORKDIR /build

# Copy Maven project files
COPY pom.xml .
COPY src ./src

# Build the JAR
RUN mvn clean package -DskipTests

# Stage 2: Build Keycloak with the provider
FROM quay.io/keycloak/keycloak:26.4.0 AS keycloak-builder

# Enable health checks and metrics
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true
ENV KC_DB=postgres

# Copy the built JAR from maven-builder stage
COPY --from=maven-builder /build/target/*.jar /opt/keycloak/providers/

# Build Keycloak with the custom provider
RUN /opt/keycloak/bin/kc.sh build

# Stage 3: Final runtime image
# Utilisons la même version que le builder
FROM quay.io/keycloak/keycloak:26.4.0

# Copy built Keycloak from builder stage
COPY --from=keycloak-builder /opt/keycloak/ /opt/keycloak/

# Enable health checks and metrics
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

# Copies the Realm export JSON file
# Note: We always name it 'realm.json'
COPY keycloak-config/eventy-realm-export.json /opt/keycloak/data/import/realm.json

# Sets the startup command. We use a multi-step process for sed to avoid 'Permission denied' errors:
# 1. Sed outputs the modified content to /tmp/temp_realm.json (always writable).
# 2. mv overwrites the original file (executed as root, which has permission).
# 3. su keycloak -c '...' starts the Keycloak server as the non-root user.
ENTRYPOINT ["/bin/sh", "-c", "sed 's|{{SECRET_ADMIN_PASSWORD}}|$SUPER_ADMIN_PASSWORD|g' /opt/keycloak/data/import/realm.json > /tmp/temp_realm.json && mv /tmp/temp_realm.json /opt/keycloak/data/import/realm.json && su keycloak -c '/opt/keycloak/bin/kc.sh start-dev --import-realm'"]