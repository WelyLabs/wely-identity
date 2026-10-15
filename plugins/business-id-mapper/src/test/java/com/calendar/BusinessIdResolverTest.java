package com.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BusinessIdResolverTest {

    private static final String KEYCLOAK_ID = "93b5339f-a3a3-45b8-b343-25ca1b6f9d7b";
    private static final String FRESH_ID = "a5f79638-abfc-4ffc-bffc-c9f1d8878e56";
    private static final String STALE_ID = "0e1c5d1a-0000-4000-8000-000000000000";

    private static final BusinessIdSource UNREACHABLE = keycloakId -> {
        throw new IOException("connection refused");
    };

    @Test
    void resolve_shouldReturnSourceAnswer_whenNothingIsCached() {
        BusinessIdResolver resolver = new BusinessIdResolver(keycloakId -> FRESH_ID);

        assertEquals(FRESH_ID, resolver.resolve(KEYCLOAK_ID, null));
    }

    @Test
    void resolve_shouldPreferSourceOverCache_whenCacheIsStale() {
        BusinessIdResolver resolver = new BusinessIdResolver(keycloakId -> FRESH_ID);

        assertEquals(FRESH_ID, resolver.resolve(KEYCLOAK_ID, STALE_ID));
    }

    @Test
    void resolve_shouldFallBackOnCache_whenSourceIsUnreachable() {
        BusinessIdResolver resolver = new BusinessIdResolver(UNREACHABLE);

        assertEquals(STALE_ID, resolver.resolve(KEYCLOAK_ID, STALE_ID));
    }

    @Test
    void resolve_shouldFail_whenSourceIsUnreachableAndNothingIsCached() {
        BusinessIdResolver resolver = new BusinessIdResolver(UNREACHABLE);

        assertThrows(IllegalStateException.class, () -> resolver.resolve(KEYCLOAK_ID, null));
        assertThrows(IllegalStateException.class, () -> resolver.resolve(KEYCLOAK_ID, ""));
    }

    @Test
    void resolve_shouldFailEvenWithACache_whenTheUserIsUnknown() {
        // A cached id for a user wely-users no longer has is exactly the 2026-10-02 failure.
        BusinessIdResolver resolver = new BusinessIdResolver(keycloakId -> {
            throw new UnknownUserException(keycloakId);
        });

        assertThrows(IllegalStateException.class, () -> resolver.resolve(KEYCLOAK_ID, STALE_ID));
    }

    // --- account deletion: wely-users answers 404 once the account is a tombstone ---

    @Test
    void resolve_shouldRefuseTheToken_evenWithACachedId_whenTheAccountWasDeleted() {
        String keycloakId = UUID.randomUUID().toString();
        String cachedId = UUID.randomUUID().toString();
        BusinessIdResolver resolver = new BusinessIdResolver(id -> {
            throw new UnknownUserException(id);
        });

        assertThrows(IllegalStateException.class, () -> resolver.resolve(keycloakId, cachedId));
    }

    @Test
    void resolve_shouldFallBackOnTheCache_whenUsersIsUnreachableDuringTheDeletionWindow() {
        // The residual window: wely-users down before the worker deleted the Keycloak user.
        String cachedId = UUID.randomUUID().toString();
        BusinessIdResolver resolver = new BusinessIdResolver(UNREACHABLE);

        assertEquals(cachedId, resolver.resolve(UUID.randomUUID().toString(), cachedId));
    }
}
