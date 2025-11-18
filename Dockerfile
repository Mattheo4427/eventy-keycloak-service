# Stage 1: Build the event listener JAR
FROM maven:3.9-eclipse-temurin-21 AS maven-builder

WORKDIR /build

# Copy Maven project files
COPY pom.xml .
COPY src ./src

# Build the JAR
RUN mvn clean package -DskipTests

# Stage 2: Build Keycloak with the provider
FROM quay.io/keycloak/keycloak:26.0.0 AS keycloak-builder

# Enable health checks and metrics
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true
ENV KC_DB=postgres

# Copy the built JAR from maven-builder stage
COPY --from=maven-builder /build/target/*.jar /opt/keycloak/providers/

# Build Keycloak with the custom provider
RUN /opt/keycloak/bin/kc.sh build

# Stage 3: Final runtime image
FROM quay.io/keycloak/keycloak:26.0.0

# Copy built Keycloak from builder stage
COPY --from=keycloak-builder /opt/keycloak/ /opt/keycloak/

# Enable health checks and metrics
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

# Copy realm export into Keycloak import directory
COPY keycloak-config/eventy-realm-export.json /opt/keycloak/data/import/realm.json

# Start Keycloak in development mode and import the realm
ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start-dev", "--import-realm"]