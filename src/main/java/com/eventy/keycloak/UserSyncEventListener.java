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
        // Gère l'auto-enregistrement
        if (event.getType() == EventType.REGISTER) {
            handleUserEvent(event);
        } 
        // Gère la première connexion (pour les utilisateurs importés ou les cas manqués)
        else if (event.getType() == EventType.LOGIN) {
            handleUserEvent(event);
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // Gère la création d'utilisateur par un administrateur
        if (event.getResourceType() == ResourceType.USER && 
            event.getOperationType() == OperationType.CREATE) {
            handleAdminUserCreation(event);
        }
    }

    /**
     * Méthode générique pour gérer les événements Keycloak (REGISTER, LOGIN).
     */
    private void handleUserEvent(Event event) {
        String userId = event.getUserId();
        RealmModel realm = session.getContext().getRealm();
        UserModel user = session.users().getUserById(realm, userId);
        
        if (user != null) {
            // Détermine le rôle à partir des attributs Keycloak (e.g., app_role: ADMIN)
            String role = getRoleFromUserAttributes(user);
            
            // Tente la synchronisation. Si l'utilisateur existe déjà, le service utilisateur 
            // devrait gérer le conflit (e.g., ignorer ou faire un UPSERT).
            syncUserToService(user, role); 
        } else {
            logger.log(Level.WARNING, "User not found for event " + event.getType() + ": " + userId);
        }
    }


    /**
     * Récupère le UserModel à partir d'un événement de création admin et initie la sync.
     */
    private void handleAdminUserCreation(AdminEvent event) {
        String resourcePath = event.getResourcePath();
        if (resourcePath != null && resourcePath.startsWith("users/")) {
            String userId = resourcePath.substring(6);
            RealmModel realm = session.getContext().getRealm();
            UserModel user = session.users().getUserById(realm, userId);
            
            if (user != null) {
                // Détermine le rôle
                String role = getRoleFromUserAttributes(user);
                syncUserToService(user, role);
            }
        }
    }
    
    /**
     * Lit l'attribut 'app_role' de l'utilisateur Keycloak.
     * Si l'attribut est défini (e.g., pour super_admin), il est utilisé. Sinon, le rôle par défaut est 'USER'.
     */
    private String getRoleFromUserAttributes(UserModel user) {
        String appRole = user.getFirstAttribute("app_role");
        
        if (appRole != null && !appRole.trim().isEmpty()) {
            return appRole.toUpperCase();
        }
        return "USER"; // Rôle par défaut
    }

    /**
     * Construit le payload JSON et l'envoie au service utilisateur.
     */
    private void syncUserToService(UserModel user, String role) { // <-- Nouvelle signature
        String username = user.getUsername();
        String email = user.getEmail();
        String firstName = user.getFirstName();
        String lastName = user.getLastName();

        // Ajout de la logique de vérification des attributs.
        if (username == null || username.trim().isEmpty() ||
            email == null || email.trim().isEmpty() ||
            firstName == null || firstName.trim().isEmpty() || 
            lastName == null || lastName.trim().isEmpty()) {
            
            logger.log(Level.WARNING, "User missing required fields or fields contain only whitespace, skipping sync for Keycloak ID: " + user.getId());
            return;
        }

        // Construction du payload avec le rôle
        String payload = buildJsonPayload(username, email, firstName, lastName, role); // <-- Nouveau

        sendToUserService(payload);
    }

    /**
     * Crée la chaîne JSON pour le DTO (inclut maintenant le rôle).
     */
    private String buildJsonPayload(String username, String email, String firstName, String lastName, String role) {
        // Escape strings to prevent JSON injection
        username = escapeJson(username);
        email = escapeJson(email);
        firstName = escapeJson(firstName);
        lastName = escapeJson(lastName);
        role = escapeJson(role); // Escape le rôle aussi

        return String.format(
            "{\"username\":\"%s\",\"email\":\"%s\",\"firstName\":\"%s\",\"lastName\":\"%s\",\"role\":\"%s\"}",
            username, email, firstName, lastName, role // <-- Nouveau paramètre
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
     * Sends the JSON payload as a POST request to the user service.
     */
    private void sendToUserService(String payload) {
        HttpURLConnection conn = null;
        try {
            // ... (logique de connexion HTTP inchangée) ...
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