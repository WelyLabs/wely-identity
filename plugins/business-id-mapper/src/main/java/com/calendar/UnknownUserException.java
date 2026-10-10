package com.calendar;

import java.io.IOException;

/** wely-users answered that it has no user for this Keycloak id. Unlike an outage, a cache must not hide it. */
final class UnknownUserException extends IOException {

    UnknownUserException(String keycloakId) {
        super("wely-users has no user for " + keycloakId);
    }
}
