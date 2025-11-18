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

/**
 * Keycloak Event Listener to synchronize user creation events (REGISTER and ADMIN CREATE)
 * to the external eventy-users-service via HTTP POST request.
 */
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
        // Handle user self-registration event
        if (event.getType() == EventType.REGISTER) {
            handleUserRegistration(event);
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // Handle admin-created users event
        if (event.getResourceType() == ResourceType.USER && 
            event.getOperationType() == OperationType.CREATE) {
            handleAdminUserCreation(event);
        }
    }

    /**
     * Retrieves the UserModel from a standard registration event and initiates sync.
     */
    private void handleUserRegistration(Event event) {
        String userId = event.getUserId();
        RealmModel realm = session.getContext().getRealm();
        // The user should exist after a successful REGISTER event
        UserModel user = session.users().getUserById(realm, userId);
        
        if (user != null) {
            syncUserToService(user);
        } else {
            logger.log(Level.WARNING, "User not found for registration event: " + userId);
        }
    }

    /**
     * Retrieves the UserModel from an admin creation event and initiates sync.
     */
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

    /**
     * Builds the JSON payload and sends it to the user service.
     */
    private void syncUserToService(UserModel user) {
        String username = user.getUsername();
        String email = user.getEmail();
        String firstName = user.getFirstName();
        String lastName = user.getLastName();

        // Validate required fields (must match the @NotBlank constraints in the Spring service DTO)
        if (username == null || username.trim().isEmpty() ||
            email == null || email.trim().isEmpty() ||
            firstName == null || firstName.trim().isEmpty() || 
            lastName == null || lastName.trim().isEmpty()) {
            
            logger.log(Level.WARNING, "User missing required fields or fields contain only whitespace, skipping sync for Keycloak ID: " + user.getId());
            return;
        }

        // Build JSON payload - only send fields that match CreateUserRequest DTO
        // ID, avatarUrl, balance, and creationDate are set by the backend (user service)
        String payload = buildJsonPayload(username, email, firstName, lastName);

        sendToUserService(payload);
    }

    /**
     * Creates the JSON string for the CreateUserRequest DTO.
     */
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

    /**
     * Escapes special characters in the string for JSON serialization.
     * Note: Since we check for null/empty values in syncUserToService, 
     * this method should not receive null or empty strings.
     */
    private String escapeJson(String value) {
        if (value == null) {
            // Should be caught by the check in syncUserToService, but kept for safety.
            return ""; 
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    /**
     * Sends the JSON payload as a POST request to the user service.
     */
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
                // If we get a 400 Bad Request, log the details for diagnosis
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