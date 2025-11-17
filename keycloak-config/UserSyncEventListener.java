package com.eventy.keycloak;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.UserModel;
import org.keycloak.models.RealmModel;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import java.util.logging.Level;

public class UserSyncEventListener implements EventListenerProvider {
    private static final Logger logger = Logger.getLogger(UserSyncEventListener.class.getName());
    
    private final KeycloakSession session;
    private final String userServiceUrl;

    public UserSyncEventListener(KeycloakSession session, String userServiceUrl) {
        this.session = session;
        this.userServiceUrl = userServiceUrl;
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.REGISTER) {
            handleUserRegistration(event);
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // Handle admin-created users
        if (event.getResourceType() == ResourceType.USER && 
            event.getOperationType() == OperationType.CREATE) {
            handleAdminUserCreation(event);
        }
    }

    private void handleUserRegistration(Event event) {
        String userId = event.getUserId();
        RealmModel realm = session.getContext().getRealm();
        UserModel user = session.users().getUserById(realm, userId);
        
        if (user != null) {
            syncUserToService(user);
        } else {
            logger.log(Level.WARNING, "User not found for registration event: " + userId);
        }
    }

    private void handleAdminUserCreation(AdminEvent event) {
        // Extract user ID from resource path (format: "users/{userId}")
        String resourcePath = event.getResourcePath();
        if (resourcePath != null && resourcePath.startsWith("users/")) {
            String userId = resourcePath.substring(6);
            RealmModel realm = session.getContext().getRealm();
            UserModel user = session.users().getUserById(realm, userId);
            
            if (user != null) {
                syncUserToService(user);
            }
        }
    }

    private void syncUserToService(UserModel user) {
        String username = user.getUsername();
        String email = user.getEmail();
        String firstName = user.getFirstName();
        String lastName = user.getLastName();

        // Validate required fields
        if (username == null || email == null || firstName == null || lastName == null) {
            logger.log(Level.WARNING, "User missing required fields, skipping sync: " + user.getId());
            return;
        }

        // Build JSON payload - only send fields that match CreateUserRequest DTO
        // avatarUrl, balance, and creationDate are set by the backend
        String payload = buildJsonPayload(username, email, firstName, lastName);

        sendToUserService(payload);
    }

    private String buildJsonPayload(String username, String email, String firstName, String lastName) {
        // Escape strings to prevent JSON injection
        username = escapeJson(username);
        email = escapeJson(email);
        firstName = escapeJson(firstName);
        lastName = escapeJson(lastName);

        return String.format(
            "{\"username\":\"%s\",\"email\":\"%s\",\"firstName\":\"%s\",\"lastName\":\"%s\"}",
            username, email, firstName, lastName
        );
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private void sendToUserService(String payload) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(userServiceUrl + "/api/users");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                logger.log(Level.INFO, "Successfully synced user to service. Response code: " + responseCode);
            } else {
                logger.log(Level.WARNING, "Failed to sync user to service. Response code: " + responseCode);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error syncing user to service: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Override
    public void close() {
        // No resources to close for this listener
    }
}