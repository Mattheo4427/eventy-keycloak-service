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
# CORRECTION 1: Le commentaire a été déplacé sur sa propre ligne, AU-DESSUS de l'instruction 'FROM'.
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

# Sets the startup command. It performs two steps:
# 1. sed: Replaces the {{SECRET_ADMIN_PASSWORD}} placeholder with the value of the $SUPER_ADMIN_PASSWORD environment variable
# 2. Keycloak: Starts the Keycloak server in development mode and imports the realm.
# CORRECTION 2: Suppression du guillemet double ("") en trop à la fin de la ligne.
CMD sh -c "sed -i 's|{{SECRET_ADMIN_PASSWORD}}|$SUPER_ADMIN_PASSWORD|g' /opt/keycloak/data/import/realm.json && /opt/keycloak/bin/kc.sh start-dev --import-realm"