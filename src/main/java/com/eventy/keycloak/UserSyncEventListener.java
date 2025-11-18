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
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Keycloak Event Listener to synchronize user creation (REGISTER, ADMIN CREATE) and first LOGIN 
 * events to the external eventy-users-service via HTTP POST request, including the application role.
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
        // Handle self-registration
        if (event.getType() == EventType.REGISTER) {
            handleUserEvent(event);
        } 
        // Handle login (for imported users or resync)
        else if (event.getType() == EventType.LOGIN) {
            handleUserEvent(event);
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // Handle user creation by an administrator
        if (event.getResourceType() == ResourceType.USER && 
            event.getOperationType() == OperationType.CREATE) {
            handleAdminUserCreation(event);
        }
    }

    /**
     * Generic method to handle Keycloak user events (REGISTER, LOGIN).
     */
    private void handleUserEvent(Event event) {
        String userId = event.getUserId();
        RealmModel realm = session.getContext().getRealm();
        UserModel user = session.users().getUserById(realm, userId);
        
        if (user != null) {
            // Determine the role from Keycloak attributes (e.g., app_role: ADMIN)
            String role = getRoleFromUserAttributes(user);
            
            // Attempt synchronization. The user service should handle UPSERT/conflict.
            syncUserToService(user, role); 
        } else {
            logger.log(Level.WARNING, "User not found for event " + event.getType() + ": " + userId);
        }
    }


    /**
     * Retrieves the UserModel from an admin creation event and initiates the sync.
     */
    private void handleAdminUserCreation(AdminEvent event) {
        String resourcePath = event.getResourcePath();
        if (resourcePath != null && resourcePath.startsWith("users/")) {
            String userId = resourcePath.substring(6);
            RealmModel realm = session.getContext().getRealm();
            UserModel user = session.users().getUserById(realm, userId);
            
            if (user != null) {
                // Determine the role
                String role = getRoleFromUserAttributes(user);
                syncUserToService(user, role);
            }
        }
    }
    
    /**
     * Reads the 'app_role' attribute from the Keycloak user.
     * If defined, it's used. Otherwise, the default role is 'USER'.
     */
    private String getRoleFromUserAttributes(UserModel user) {
        String appRole = user.getFirstAttribute("app_role");
        
        if (appRole != null && !appRole.trim().isEmpty()) {
            return appRole.toUpperCase();
        }
        return "USER"; // Default role
    }

    /**
     * Constructs the JSON payload and sends it to the user service.
     */
    private void syncUserToService(UserModel user, String role) { 
        String username = user.getUsername();
        String email = user.getEmail();
        String firstName = user.getFirstName();
        String lastName = user.getLastName();

        // Pre-validation to skip sync if required fields are missing or only contain whitespace.
        if (username == null || username.trim().isEmpty() ||
            email == null || email.trim().isEmpty() ||
            firstName == null || firstName.trim().isEmpty() || 
            lastName == null || lastName.trim().isEmpty()) {
            
            logger.log(Level.WARNING, "User missing required fields or fields contain only whitespace, skipping sync for Keycloak ID: " + user.getId());
            return;
        }

        // Build the payload with the role
        String keycloakId = user.getId();
        String payload = buildJsonPayload(keycloakId, username, email, firstName, lastName, role);

        sendToUserService(payload);
    }

    /**
     * Creates the JSON string for the DTO (includes the role).
     */
    private String buildJsonPayload(String id, String username, String email, String firstName, String lastName, String role) {
        
        return String.format(
            "{\"id\":\"%s\",\"username\":\"%s\",\"email\":\"%s\",\"firstName\":\"%s\",\"lastName\":\"%s\",\"role\":\"%s\"}",
            escapeJson(id), escapeJson(username), escapeJson(email),
            escapeJson(firstName), escapeJson(lastName), escapeJson(role)
        );
    }

    /**
     * Escapes special characters in the string for JSON serialization.
     */
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

    /**
     * Sends the JSON payload as a POST request to the user service, 
     * logging the payload and the error body if the response is not successful.
     */
    private void sendToUserService(String payload) {
        HttpURLConnection conn = null;
        try {
            // Use the internal endpoint
            URL url = new URL(userServiceUrl + "/api/users/internal/keycloak-sync");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            
            // Add the shared secret header
            String secret = System.getenv("KEYCLOAK_SYNC_SECRET");
            if (secret != null && !secret.isEmpty()) {
                conn.setRequestProperty("X-Keycloak-Secret", secret);
            } else {
                logger.log(Level.WARNING, "KEYCLOAK_SYNC_SECRET not set, sync may fail");
            }
            
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            // Log the actual payload being sent
            logger.log(Level.INFO, "Attempting to sync user with payload: " + payload); 

            // Write payload to output stream
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                logger.log(Level.INFO, "Successfully synced user to service. Response code: " + responseCode);
            } else {
                logger.log(Level.WARNING, "Failed to sync user to service. Response code: " + responseCode);
                
                // CRUCIAL: Read the error body if the response is not 2xx
                try (InputStream es = conn.getErrorStream()) {
                    if (es != null) {
                        // Use try-with-resources and a BufferedReader for safer reading
                        String errorBody = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                            
                        // Log the exact validation error returned by the User Service
                        logger.log(Level.SEVERE, "User Service Error Body (400 or other): " + errorBody);
                    }
                } catch (IOException streamEx) {
                    logger.log(Level.FINE, "Could not read error stream.", streamEx);
                }
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