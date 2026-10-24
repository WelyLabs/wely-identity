package com.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class UsersServiceClientTest {

    private static final String KEYCLOAK_ID = "93b5339f-a3a3-45b8-b343-25ca1b6f9d7b";
    private static final String BUSINESS_ID = "a5f79638-abfc-4ffc-bffc-c9f1d8878e56";
    private static final String SECRET = "test-secret";
    private static final String REALM = "wely-dev";

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedSecret = new AtomicReference<>();
    private final AtomicReference<String> receivedMethod = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = BUSINESS_ID;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            receivedSecret.set(exchange.getRequestHeaders().getFirst(UsersServiceClient.SECRET_HEADER));
            receivedMethod.set(exchange.getRequestMethod());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void resolve_shouldReturnBusinessIdAndSendSecret_whenUsersAnswers200() throws IOException {
        String result = new UsersServiceClient(baseUrl, SECRET).resolve(KEYCLOAK_ID, REALM);

        assertEquals(BUSINESS_ID, result);
        assertEquals(UsersServiceClient.RESOLVE_PATH + KEYCLOAK_ID, requestedPath.get());
        assertEquals(SECRET, receivedSecret.get());
    }

    @Test
    void resolve_shouldStripQuotes_whenBodyIsAJsonString() throws IOException {
        body = "\"" + BUSINESS_ID + "\"\n";

        assertEquals(BUSINESS_ID, new UsersServiceClient(baseUrl, SECRET).resolve(KEYCLOAK_ID, REALM));
    }

    @Test
    void resolve_shouldFail_whenUsersAnswersAnError() {
        status = 502;

        assertThrows(IOException.class, () -> new UsersServiceClient(baseUrl, SECRET).resolve(KEYCLOAK_ID, REALM));
    }

    @Test
    void resolve_shouldFail_whenBodyIsNotAUuid() {
        body = "<html>proxy error</html>";

        assertThrows(IOException.class, () -> new UsersServiceClient(baseUrl, SECRET).resolve(KEYCLOAK_ID, REALM));
    }

    @Test
    void resolve_shouldFailWithoutCalling_whenSecretIsMissing() {
        assertThrows(IOException.class, () -> new UsersServiceClient(baseUrl, " ").resolve(KEYCLOAK_ID, REALM));
        assertThrows(IOException.class, () -> new UsersServiceClient(baseUrl, null).resolve(KEYCLOAK_ID, REALM));
        assertNull(requestedPath.get());
    }

    @Test
    void resolve_shouldFail_whenUsersIsUnreachable() {
        server.stop(0);

        assertThrows(IOException.class, () -> new UsersServiceClient(baseUrl, SECRET).resolve(KEYCLOAK_ID, REALM));
    }

    @Test
    void resolve_shouldFailAsUnknownUser_whenUsersAnswers404() {
        status = 404;

        assertThrows(UnknownUserException.class, () -> new UsersServiceClient(baseUrl, SECRET).resolve(KEYCLOAK_ID, REALM));
    }

    @Test
    void provision_shouldPostTheIdentityAndReturnTheBusinessId() throws IOException {
        String result = new UsersServiceClient(baseUrl, SECRET).provision(KEYCLOAK_ID, "lea", "Léa", "Martin", REALM);

        assertEquals(BUSINESS_ID, result);
        assertEquals("POST", receivedMethod.get());
        assertEquals(UsersServiceClient.PROVISION_PATH, requestedPath.get());
        assertEquals(SECRET, receivedSecret.get());
        assertEquals("{\"keycloakId\":\"" + KEYCLOAK_ID + "\",\"username\":\"lea\",\"firstName\":\"Léa\",\"lastName\":\"Martin\"}",
                receivedBody.get());
    }

    @Test
    void provision_shouldFail_whenUsersAnswersAnError() {
        status = 500;

        assertThrows(IOException.class,
                () -> new UsersServiceClient(baseUrl, SECRET).provision(KEYCLOAK_ID, "lea", "Léa", "Martin", REALM));
    }

    @Test
    void provision_shouldFailWithoutCalling_whenSecretIsMissing() {
        assertThrows(IOException.class,
                () -> new UsersServiceClient(baseUrl, null).provision(KEYCLOAK_ID, "lea", "Léa", "Martin", REALM));
        assertNull(requestedPath.get());
    }

    @Test
    void baseUrlFrom_shouldPreferConfiguredUrl() {
        assertEquals("http://users:8082", UsersServiceClient.baseUrlFrom(" http://users:8082 ", "prod"));
    }

    @Test
    void baseUrlFrom_shouldFallBackOnProfile_whenNothingIsConfigured() {
        assertEquals("http://host.docker.internal:8082", UsersServiceClient.baseUrlFrom(null, "dev"));
        assertEquals("http://wely-users-service:8082", UsersServiceClient.baseUrlFrom("", "prod"));
    }

    @Test
    void fromEnvironment_shouldBuildAClient() {
        assertNotNull(UsersServiceClient.fromEnvironment());
    }

    // --- account deletion -----------------------------------------------------

    @Test
    void resolve_shouldFailAsUnknownUser_whenUsersAnswers404WithAProblemBody() {
        // What wely-users answers for the original Keycloak id of a deleted account.
        status = 404;
        body = "{\"status\":404,\"code\":\"USR-BUS-001\"}";
        String keycloakId = UUID.randomUUID().toString();

        assertThrows(UnknownUserException.class, () -> new UsersServiceClient(baseUrl, SECRET).resolve(keycloakId, REALM));
    }

    @Test
    void resolve_shouldTreatA410AsAnOutage_whenUsersAnswersGone() {
        // Why resolve must never answer 410: as an outage, the resolver would mint a token from its cache.
        status = 410;
        body = "{\"status\":410,\"code\":\"USR-BUS-012\"}";
        String keycloakId = UUID.randomUUID().toString();

        IOException error = assertThrows(IOException.class,
                () -> new UsersServiceClient(baseUrl, SECRET).resolve(keycloakId, REALM));
        assertFalse(error instanceof UnknownUserException);
    }

    // --- one Keycloak, one realm per environment: the URL follows the realm ---

    @Test
    void baseUrlFor_shouldSubstituteTheEnvironmentOfTheRealm() throws IOException {
        UsersServiceClient client = new UsersServiceClient(
                "http://wely-users-service.wely-{env}.svc.cluster.local:8082", SECRET);

        assertEquals("http://wely-users-service.wely-dev.svc.cluster.local:8082",
                client.baseUrlFor("wely-dev"));
        assertEquals("http://wely-users-service.wely-prod.svc.cluster.local:8082",
                client.baseUrlFor("wely-prod"));
    }

    @Test
    void baseUrlFor_shouldLeaveAUrlWithoutPlaceholderAlone() throws IOException {
        UsersServiceClient client = new UsersServiceClient("http://users:8082", SECRET);

        assertEquals("http://users:8082", client.baseUrlFor("wely-dev"));
        assertEquals("http://users:8082", client.baseUrlFor("anything"));
    }

    @Test
    void baseUrlFor_shouldRefuseARealmThatNamesNoEnvironment() {
        // Guessing here is the silent bug the placeholder exists to prevent: a token minted for one
        // realm would carry the business id of another environment's user.
        UsersServiceClient client = new UsersServiceClient("http://users.wely-{env}:8082", SECRET);

        assertThrows(IOException.class, () -> client.baseUrlFor("master"));
        assertThrows(IOException.class, () -> client.baseUrlFor("wely-"));
        assertThrows(IOException.class, () -> client.baseUrlFor(null));
    }
}
