# Use official Keycloak image
FROM quay.io/keycloak/keycloak:26.4.0

# Enable health checks and metrics
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

# Copy realm export into Keycloak import directory
COPY keycloak-config/eventy-realm-export.json /opt/keycloak/data/import/realm.json

# Start Keycloak in development mode and import the realm
ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start-dev", "--import-realm"]
