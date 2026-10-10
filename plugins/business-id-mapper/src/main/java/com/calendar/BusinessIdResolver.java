package com.calendar;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which business id goes into a token.
 *
 * <p>wely-users is always asked first, because it is the only place that knows whether the
 * user still exists. The id cached on the Keycloak user is only a fallback for when wely-users
 * cannot be reached, so that an outage does not block every login.
 *
 * <p>Reading the cache first — what the mapper used to do — means a user whose row is lost
 * keeps receiving tokens for an id that no longer exists, with nothing ever correcting it.
 * That happened on 2026-10-02: every real account answered 404 on its profile until the rows
 * were restored by hand.
 */
final class BusinessIdResolver {

    private static final Logger log = LoggerFactory.getLogger(BusinessIdResolver.class);

    private final BusinessIdSource source;

    BusinessIdResolver(BusinessIdSource source) {
        this.source = source;
    }

    /**
     * @param keycloakId the Keycloak user id
     * @param cachedId   the business id previously stored on the Keycloak user, or {@code null}
     * @return the business id to put in the token
     * @throws IllegalStateException when wely-users has no such user, or is unreachable and nothing is cached
     */
    String resolve(String keycloakId, String cachedId) {
        try {
            return source.resolve(keycloakId);
        } catch (UnknownUserException e) {
            // Users are created at registration: no user means no account, cache or not.
            throw new IllegalStateException("No wely-users user for " + keycloakId, e);
        } catch (IOException e) {
            if (cachedId == null || cachedId.isBlank()) {
                throw new IllegalStateException("Cannot resolve the business id of " + keycloakId, e);
            }
            log.warn("wely-users unavailable, using the cached business id of {}: {}", keycloakId, e.getMessage());
            return cachedId;
        }
    }
}
