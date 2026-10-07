package com.calendar;

import org.keycloak.models.*;
import org.keycloak.protocol.oidc.mappers.*;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class BusinessIdMapper extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper, OIDCIDTokenMapper {

    public static final String PROVIDER_ID = "jit-business-id-mapper";
    static final String BUSINESS_ID_ATTRIBUTE = "businessId";
    static final String DEFAULT_CLAIM_NAME = "businessId";

    private static final Logger log = LoggerFactory.getLogger(BusinessIdMapper.class);

    private static final List<ProviderConfigProperty> configProperties = new ArrayList<>();

    static {
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(configProperties);
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, BusinessIdMapper.class);
    }

    private final BusinessIdResolver resolver;

    /** Used by Keycloak, which loads providers through {@link java.util.ServiceLoader}. */
    public BusinessIdMapper() {
        this(new BusinessIdResolver(UsersServiceClient.fromEnvironment()));
    }

    BusinessIdMapper(BusinessIdResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configProperties;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "JIT Business ID Mapper";
    }

    @Override
    public String getHelpText() {
        return "Resolves the application business id through wely-users and adds it as a claim";
    }

    @Override
    public String getDisplayCategory() {
        return "Token Mapper";
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
            KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx) {

        UserModel user = userSession.getUser();
        String cachedId = user.getFirstAttribute(BUSINESS_ID_ATTRIBUTE);
        String businessId = resolver.resolve(user.getId(), cachedId);

        // Keeps the fallback current, and repairs a cache that points at a lost user.
        if (!businessId.equals(cachedId)) {
            log.info("Business id of {} set to {} (was {})", user.getId(), businessId, cachedId);
            user.setSingleAttribute(BUSINESS_ID_ATTRIBUTE, businessId);
        }

        String claimName = mappingModel.getConfig().get(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME);
        token.getOtherClaims().put(claimName != null ? claimName : DEFAULT_CLAIM_NAME, businessId);
    }
}
