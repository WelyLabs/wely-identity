package com.calendar;

import java.io.IOException;

/**
 * Authoritative source of the business id that matches a Keycloak user.
 */
interface BusinessIdSource {

    /**
     * Returns the business id of the given Keycloak user.
     *
     * @throws UnknownUserException when the source has no such user
     * @throws IOException when the source cannot be reached or gives an unusable answer
     */
    String resolve(String keycloakId) throws IOException;
}
