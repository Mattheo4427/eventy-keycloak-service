package com.eventy.keycloak;

import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class UserSyncEventListenerFactory implements EventListenerProviderFactory {
    private String userServiceUrl;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new UserSyncEventListener(session, userServiceUrl);
    }

    @Override
    public void init(Config.Scope config) {
        // Read from environment variable
        userServiceUrl = System.getenv("USER_SERVICE_URL");
        if (userServiceUrl == null || userServiceUrl.isEmpty()) {
            throw new IllegalStateException("USER_SERVICE_URL environment variable is not set");
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return "user-sync-event-listener";
    }
}