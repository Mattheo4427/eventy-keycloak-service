# Use official Keycloak image
FROM quay.io/keycloak/keycloak:26.0.0 AS builder

# Enable health checks and metrics
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

# Set database vendor (PostgreSQL recommended for production)
ENV KC_DB=postgres

# Copy your custom event listener JAR
COPY target/keycloak-user-sync-*.jar /opt/keycloak/providers/

# Build Keycloak with the custom provider
RUN /opt/keycloak/bin/kc.sh build

# Final runtime image
FROM quay.io/keycloak/keycloak:26.0.0

# Copy built Keycloak from builder stage
COPY --from=builder /opt/keycloak/ /opt/keycloak/

# Enable health checks and metrics
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

# Copy realm export into Keycloak import directory
COPY keycloak-config/eventy-realm-export.json /opt/keycloak/data/import/realm.json

# Environment variable for user service URL (set in docker-compose or k8s)
ENV USER_SERVICE_URL=http://user-service:8080

# Start Keycloak in development mode and import the realm
ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start-dev", "--import-realm"]