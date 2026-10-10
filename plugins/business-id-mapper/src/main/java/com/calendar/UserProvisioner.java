package com.calendar;

import java.io.IOException;

/** Creates the wely-users user that matches a newly registered Keycloak user. */
interface UserProvisioner {

    /**
     * Returns the business id of the created user, or of the existing one: the call is idempotent.
     *
     * @throws IOException when wely-users cannot be reached or gives an unusable answer
     */
    String provision(String keycloakId, String username, String firstName, String lastName) throws IOException;
}
