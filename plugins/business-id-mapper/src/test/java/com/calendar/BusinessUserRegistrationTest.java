package com.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.AuthenticationFlowException;
import org.keycloak.authentication.FormContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.UserModel;

class BusinessUserRegistrationTest {

    private static final String KEYCLOAK_ID = "93b5339f-a3a3-45b8-b343-25ca1b6f9d7b";
    private static final String BUSINESS_ID = "a5f79638-abfc-4ffc-bffc-c9f1d8878e56";

    private final Map<String, String> attributes = new HashMap<>();
    private final List<String> provisionCalls = new ArrayList<>();
    private boolean rollbackOnly;

    @Test
    void success_shouldProvisionTheUserAndCacheItsBusinessId() {
        BusinessUserRegistration step = new BusinessUserRegistration((keycloakId, username, first, last) -> {
            provisionCalls.add(keycloakId + "|" + username + "|" + first + "|" + last);
            return BUSINESS_ID;
        });

        step.success(fakeContext());

        assertEquals(List.of(KEYCLOAK_ID + "|lea|Léa|Martin"), provisionCalls);
        assertEquals(BUSINESS_ID, attributes.get(BusinessIdMapper.BUSINESS_ID_ATTRIBUTE));
        assertFalse(rollbackOnly);
    }

    @Test
    void success_shouldRollBackTheRegistration_whenUsersFails() {
        BusinessUserRegistration step = new BusinessUserRegistration((keycloakId, username, first, last) -> {
            throw new IOException("wely-users answered 503");
        });

        AuthenticationFlowException failure =
                assertThrows(AuthenticationFlowException.class, () -> step.success(fakeContext()));

        // Without rollback-only, Keycloak renders the error page and commits the account anyway.
        assertTrue(rollbackOnly);
        assertEquals(AuthenticationFlowError.GENERIC_AUTHENTICATION_ERROR, failure.getError());
        assertEquals(BusinessUserRegistration.UNAVAILABLE_MESSAGE, failure.getUserErrorMessage());
        assertNull(attributes.get(BusinessIdMapper.BUSINESS_ID_ATTRIBUTE));
    }

    @Test
    void validate_shouldAcceptEveryForm() {
        // The step adds no field: the Keycloak user does not exist yet at validation time.
        BusinessUserRegistration step = new BusinessUserRegistration((k, u, f, l) -> BUSINESS_ID);
        boolean[] succeeded = { false };
        org.keycloak.authentication.ValidationContext context = (org.keycloak.authentication.ValidationContext)
                Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] { org.keycloak.authentication.ValidationContext.class },
                        (proxy, method, args) -> {
                            if (method.getName().equals("success")) {
                                succeeded[0] = true;
                                return null;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });

        step.validate(context);

        assertTrue(succeeded[0]);
    }

    @Test
    void factory_shouldOnlyOfferRequiredOrDisabled() {
        BusinessUserRegistration step = new BusinessUserRegistration((k, u, f, l) -> BUSINESS_ID);

        assertEquals(BusinessUserRegistration.PROVIDER_ID, step.getId());
        assertEquals(2, step.getRequirementChoices().length);
        assertFalse(step.requiresUser());
        assertFalse(step.isConfigurable());
        assertTrue(step.getConfigProperties().isEmpty());
    }

    private FormContext fakeContext() {
        UserModel user = (UserModel) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { UserModel.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> KEYCLOAK_ID;
                    case "getUsername" -> "lea";
                    case "getFirstName" -> "Léa";
                    case "getLastName" -> "Martin";
                    case "setSingleAttribute" -> {
                        attributes.put((String) args[0], (String) args[1]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        KeycloakTransactionManager transactions = (KeycloakTransactionManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { KeycloakTransactionManager.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("setRollbackOnly")) {
                        rollbackOnly = true;
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        KeycloakSession session = (KeycloakSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { KeycloakSession.class }, (proxy, method, args) -> {
                    if (method.getName().equals("getTransactionManager")) {
                        return transactions;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (FormContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { FormContext.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getUser" -> user;
                    case "getSession" -> session;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
