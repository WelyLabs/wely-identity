package com.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.representations.IDToken;

class BusinessIdMapperTest {

    private static final String KEYCLOAK_ID = "93b5339f-a3a3-45b8-b343-25ca1b6f9d7b";
    private static final String REALM = "wely-dev";
    private static final String FRESH_ID = "a5f79638-abfc-4ffc-bffc-c9f1d8878e56";
    private static final String STALE_ID = "0e1c5d1a-0000-4000-8000-000000000000";

    /** Attributes of the fake Keycloak user; {@code setSingleAttribute} writes here. */
    private final Map<String, String> attributes = new HashMap<>();
    private int attributeWrites;

    @Test
    void setClaim_shouldAddResolvedIdAndCacheIt_whenNothingIsCached() {
        IDToken token = map((keycloakId, realm) -> FRESH_ID, null);

        assertEquals(FRESH_ID, token.getOtherClaims().get(BusinessIdMapper.DEFAULT_CLAIM_NAME));
        assertEquals(FRESH_ID, attributes.get(BusinessIdMapper.BUSINESS_ID_ATTRIBUTE));
    }

    @Test
    void setClaim_shouldReplaceStaleCache_whenUsersReturnsAnotherId() {
        attributes.put(BusinessIdMapper.BUSINESS_ID_ATTRIBUTE, STALE_ID);

        IDToken token = map((keycloakId, realm) -> FRESH_ID, null);

        assertEquals(FRESH_ID, token.getOtherClaims().get(BusinessIdMapper.DEFAULT_CLAIM_NAME));
        assertEquals(FRESH_ID, attributes.get(BusinessIdMapper.BUSINESS_ID_ATTRIBUTE));
    }

    @Test
    void setClaim_shouldNotRewriteAttribute_whenCacheIsCurrent() {
        attributes.put(BusinessIdMapper.BUSINESS_ID_ATTRIBUTE, FRESH_ID);

        map((keycloakId, realm) -> FRESH_ID, null);

        assertEquals(0, attributeWrites);
    }

    @Test
    void setClaim_shouldUseCachedId_whenUsersIsUnreachable() {
        attributes.put(BusinessIdMapper.BUSINESS_ID_ATTRIBUTE, STALE_ID);

        IDToken token = map((keycloakId, realm) -> { throw new IOException("down"); }, null);

        assertEquals(STALE_ID, token.getOtherClaims().get(BusinessIdMapper.DEFAULT_CLAIM_NAME));
        assertEquals(0, attributeWrites);
    }

    @Test
    void setClaim_shouldFail_whenUsersIsUnreachableAndNothingIsCached() {
        assertThrows(IllegalStateException.class, () -> map((keycloakId, realm) -> { throw new IOException("down"); }, null));
        assertFalse(attributes.containsKey(BusinessIdMapper.BUSINESS_ID_ATTRIBUTE));
    }

    @Test
    void setClaim_shouldUseConfiguredClaimName() {
        IDToken token = map((keycloakId, realm) -> FRESH_ID, "appUserId");

        assertEquals(FRESH_ID, token.getOtherClaims().get("appUserId"));
        assertFalse(token.getOtherClaims().containsKey(BusinessIdMapper.DEFAULT_CLAIM_NAME));
    }

    @Test
    void setClaim_shouldResolveWithTheKeycloakUserId() {
        String[] asked = new String[1];

        map((keycloakId, realm) -> { asked[0] = keycloakId; return FRESH_ID; }, null);

        assertEquals(KEYCLOAK_ID, asked[0]);
    }

    @Test
    void getId_shouldReturnProviderId() {
        assertEquals(BusinessIdMapper.PROVIDER_ID, mapper((keycloakId, realm) -> FRESH_ID).getId());
    }

    @Test
    void getConfigProperties_shouldExposeClaimNameSetting() {
        assertTrue(mapper((keycloakId, realm) -> FRESH_ID).getConfigProperties().stream()
                .anyMatch(p -> OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME.equals(p.getName())));
    }

    @Test
    void displayTexts_shouldBeSet() {
        BusinessIdMapper mapper = mapper((keycloakId, realm) -> FRESH_ID);

        assertFalse(mapper.getDisplayType().isBlank());
        assertFalse(mapper.getDisplayCategory().isBlank());
        assertFalse(mapper.getHelpText().isBlank());
    }

    private IDToken map(BusinessIdSource source, String claimName) {
        ProtocolMapperModel model = new ProtocolMapperModel();
        Map<String, String> config = new HashMap<>();
        if (claimName != null) {
            config.put(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, claimName);
        }
        model.setConfig(config);

        IDToken token = new IDToken();
        mapper(source).setClaim(token, model, fakeSession(), null, null);
        return token;
    }

    private static BusinessIdMapper mapper(BusinessIdSource source) {
        return new BusinessIdMapper(new BusinessIdResolver(source));
    }

    private UserSessionModel fakeSession() {
        UserModel user = (UserModel) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { UserModel.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> KEYCLOAK_ID;
                    case "getFirstAttribute" -> attributes.get((String) args[0]);
                    case "setSingleAttribute" -> {
                        attributeWrites++;
                        attributes.put((String) args[0], (String) args[1]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        // One Keycloak serves several realms, so the mapper asks the session which one it is in.
        RealmModel realm = (RealmModel) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { RealmModel.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> REALM;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (UserSessionModel) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { UserSessionModel.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getUser" -> user;
                    case "getRealm" -> realm;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void setClaim_shouldMintNoTokenAndLeaveTheAttribute_whenTheAccountWasDeleted() {
        String cachedId = UUID.randomUUID().toString();
        attributes.put(BusinessIdMapper.BUSINESS_ID_ATTRIBUTE, cachedId);
        IDToken token = new IDToken();
        ProtocolMapperModel model = new ProtocolMapperModel();
        model.setConfig(new HashMap<>());

        assertThrows(IllegalStateException.class, () -> mapper((keycloakId, realm) -> {
            throw new UnknownUserException(keycloakId);
        }).setClaim(token, model, fakeSession(), null, null));

        assertFalse(token.getOtherClaims().containsKey(BusinessIdMapper.DEFAULT_CLAIM_NAME));
        assertEquals(cachedId, attributes.get(BusinessIdMapper.BUSINESS_ID_ATTRIBUTE));
        assertEquals(0, attributeWrites);
    }
}
