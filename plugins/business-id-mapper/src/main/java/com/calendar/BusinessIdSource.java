package com.calendar;

import java.io.IOException;

/**
 * Authoritative source of the business id that matches a Keycloak user.
 */
interface BusinessIdSource {

    /**
     * Returns the business id of the given Keycloak user.
     *
     * @param realm the realm being authenticated: one Keycloak serves several, each with its own
     *              wely-users, so the source cannot know which one from the user alone
     * @throws UnknownUserException when the source has no such user
     * @throws IOException when the source cannot be reached or gives an unusable answer
     */
    String resolve(String keycloakId, String realm) throws IOException;
}
