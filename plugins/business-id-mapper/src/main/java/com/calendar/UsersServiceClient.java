package com.calendar;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.keycloak.util.JsonSerialization;

/**
 * Calls wely-users: to create a user at registration, and to read its business id at login.
 *
 * <p>The calls are blocking on purpose: Keycloak runs form actions and protocol mappers
 * synchronously, so there is nothing to hand a reactive result to.
 */
final class UsersServiceClient implements BusinessIdSource, UserProvisioner {

    static final String RESOLVE_PATH = "/user-service/profile/resolve/";
    static final String PROVISION_PATH = "/user-service/profile/provision";
    static final String SECRET_HEADER = "X-Internal-Secret";

    private static final String LOCAL_DEV_URL = "http://host.docker.internal:8082";
    private static final String CLUSTER_URL = "http://wely-users-service:8082";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    // Creating a user costs more than reading one, and the person is waiting on a form, not a token.
    private static final Duration PROVISION_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final String baseUrl;
    private final String secret;

    UsersServiceClient(String baseUrl, String secret) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build();
        this.baseUrl = baseUrl;
        this.secret = secret;
    }

    /**
     * Builds a client from the environment Kubernetes injects into the Keycloak pod.
     *
     * <p>A missing {@code INTERNAL_SECRET} is not rejected here: the mapper is instantiated
     * when Keycloak loads its providers, and failing there would take the whole server down.
     * It is rejected on every call instead, which fails the login loudly.
     */
    static UsersServiceClient fromEnvironment() {
        return new UsersServiceClient(
                baseUrlFrom(System.getenv("USERS_API_URL"), System.getProperty("quarkus.profile", "prod")),
                System.getenv("INTERNAL_SECRET"));
    }

    static String baseUrlFrom(String configuredUrl, String quarkusProfile) {
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            return configuredUrl.trim();
        }
        return quarkusProfile.contains("dev") ? LOCAL_DEV_URL : CLUSTER_URL;
    }

    @Override
    public String resolve(String keycloakId) throws IOException {
        HttpRequest request = authorized(URI.create(baseUrl + RESOLVE_PATH + keycloakId), TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() == 404) {
            throw new UnknownUserException(keycloakId);
        }
        return businessIdFrom(response, keycloakId);
    }

    @Override
    public String provision(String keycloakId, String username, String firstName, String lastName)
            throws IOException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("keycloakId", keycloakId);
        body.put("username", username);
        body.put("firstName", firstName);
        body.put("lastName", lastName);

        HttpRequest request = authorized(URI.create(baseUrl + PROVISION_PATH), PROVISION_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonSerialization.writeValueAsString(body)))
                .build();

        return businessIdFrom(send(request), keycloakId);
    }

    private HttpRequest.Builder authorized(URI uri, Duration timeout) throws IOException {
        // Must match app.internal-secret on the wely-users side. No fallback on purpose:
        // a well-known default would authenticate anyone who reads this repository.
        if (secret == null || secret.isBlank()) {
            throw new IOException("INTERNAL_SECRET is not set, cannot call wely-users");
        }
        return HttpRequest.newBuilder(uri).timeout(timeout).header(SECRET_HEADER, secret);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling wely-users", e);
        }
    }

    private static String businessIdFrom(HttpResponse<String> response, String keycloakId) throws IOException {
        if (response.statusCode() != 200) {
            throw new IOException("wely-users answered " + response.statusCode() + " for " + keycloakId);
        }

        // The endpoints return a bare string; strip quotes in case it is ever serialised as JSON.
        String businessId = response.body().trim().replace("\"", "");
        try {
            return UUID.fromString(businessId).toString();
        } catch (IllegalArgumentException e) {
            throw new IOException("wely-users returned a malformed business id: '" + businessId + "'", e);
        }
    }
}
