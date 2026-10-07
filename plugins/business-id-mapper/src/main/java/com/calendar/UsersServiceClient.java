package com.calendar;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Asks wely-users for the business id of a Keycloak user.
 *
 * <p>The call is blocking on purpose: protocol mappers run synchronously inside token
 * issuance, so there is nothing to hand a reactive result to.
 */
final class UsersServiceClient implements BusinessIdSource {

    static final String RESOLVE_PATH = "/user-service/profile/resolve/";
    static final String SECRET_HEADER = "X-Internal-Secret";

    private static final String LOCAL_DEV_URL = "http://host.docker.internal:8082";
    private static final String CLUSTER_URL = "http://wely-users-service:8082";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

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
        // Must match app.internal-secret on the wely-users side. No fallback on purpose:
        // a well-known default would authenticate anyone who reads this repository.
        if (secret == null || secret.isBlank()) {
            throw new IOException("INTERNAL_SECRET is not set, cannot call wely-users");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + RESOLVE_PATH + keycloakId))
                .timeout(TIMEOUT)
                .header(SECRET_HEADER, secret)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling wely-users", e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("wely-users answered " + response.statusCode() + " for " + keycloakId);
        }

        // The endpoint returns a bare string; strip quotes in case it is ever serialised as JSON.
        String businessId = response.body().trim().replace("\"", "");
        try {
            return UUID.fromString(businessId).toString();
        } catch (IllegalArgumentException e) {
            throw new IOException("wely-users returned a malformed business id: '" + businessId + "'", e);
        }
    }
}
