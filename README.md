# Eventy Keycloak Service

Keycloak service for Eventy. Handles authentication, authorization, and user management.

## Quick Start

Run with Docker:

```bash
docker run -d \
  -p 8080:8080 \
  -v ./keycloak-config:/opt/keycloak/data/import \
  --name eventy-keycloak-service \
  quay.io/keycloak/keycloak:26.4.0 \
  start-dev --import-realm
```

## Environment Variables

KEYCLOAK_ADMIN – admin username

KEYCLOAK_ADMIN_PASSWORD – admin password

KC_DB_URL, KC_DB_USERNAME, KC_DB_PASSWORD – database connection

## API

Token: POST /realms/master/protocol/openid-connect/token

Admin: /admin/realms/{realm}/users