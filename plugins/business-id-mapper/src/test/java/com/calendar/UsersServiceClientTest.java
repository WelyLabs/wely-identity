package com.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class UsersServiceClientTest {

    private static final String KEYCLOAK_ID = "93b5339f-a3a3-45b8-b343-25ca1b6f9d7b";
    private static final String BUSINESS_ID = "a5f79638-abfc-4ffc-bffc-c9f1d8878e56";
    private static final String SECRET = "test-secret";

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
        String result = new UsersServiceClient(baseUrl, SECRET).resolve(KEYCLOAK_ID);

        assertEquals(BUSINESS_ID, result);
        assertEquals(UsersServiceClient.RESOLVE_PATH + KEYCLOAK_ID, requestedPath.get());
        assertEquals(SECRET, receivedSecret.get());
    }

    @Test
    void resolve_shouldStripQuotes_whenBodyIsAJsonString() throws IOException {
        body = "\"" + BUSINESS_ID + "\"\n";

        assertEquals(BUSINESS_ID, new UsersServiceClient(baseUrl, SECRET).resolve(KEYCLOAK_ID));
    }

    @Test
    void resolve_shouldFail_whenUsersAnswersAnError() {
        status = 502;

        assertThrows(IOException.class, () -> new UsersServiceClient(baseUrl, SECRET).resolve(KEYCLOAK_ID));
    }

    @Test
    void resolve_shouldFail_whenBodyIsNotAUuid() {
        body = "<html>proxy error</html>";

        assertThrows(IOException.class, () -> new UsersServiceClient(baseUrl, SECRET).resolve(KEYCLOAK_ID));
    }

    @Test
    void resolve_shouldFailWithoutCalling_whenSecretIsMissing() {
        assertThrows(IOException.class, () -> new UsersServiceClient(baseUrl, " ").resolve(KEYCLOAK_ID));
        assertThrows(IOException.class, () -> new UsersServiceClient(baseUrl, null).resolve(KEYCLOAK_ID));
        assertNull(requestedPath.get());
    }

    @Test
    void resolve_shouldFail_whenUsersIsUnreachable() {
        server.stop(0);

        assertThrows(IOException.class, () -> new UsersServiceClient(baseUrl, SECRET).resolve(KEYCLOAK_ID));
    }

    @Test
    void resolve_shouldFailAsUnknownUser_whenUsersAnswers404() {
        status = 404;

        assertThrows(UnknownUserException.class, () -> new UsersServiceClient(baseUrl, SECRET).resolve(KEYCLOAK_ID));
    }

    @Test
    void provision_shouldPostTheIdentityAndReturnTheBusinessId() throws IOException {
        String result = new UsersServiceClient(baseUrl, SECRET).provision(KEYCLOAK_ID, "lea", "Léa", "Martin");

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
                () -> new UsersServiceClient(baseUrl, SECRET).provision(KEYCLOAK_ID, "lea", "Léa", "Martin"));
    }

    @Test
    void provision_shouldFailWithoutCalling_whenSecretIsMissing() {
        assertThrows(IOException.class,
                () -> new UsersServiceClient(baseUrl, null).provision(KEYCLOAK_ID, "lea", "Léa", "Martin"));
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
}
