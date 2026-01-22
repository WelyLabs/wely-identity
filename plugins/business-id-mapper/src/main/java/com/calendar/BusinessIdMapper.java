package com.calendar;

import org.keycloak.models.*;
import org.keycloak.protocol.oidc.mappers.*;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class BusinessIdMapper extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper, OIDCIDTokenMapper {

    public static final String PROVIDER_ID = "jit-business-id-mapper";
    private static final Logger log = LoggerFactory.getLogger(BusinessIdMapper.class);

    private static final List<ProviderConfigProperty> configProperties = new ArrayList<>();

    static {
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(configProperties);
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, BusinessIdMapper.class);
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configProperties;
    }

    @Override
    public String getId() { return PROVIDER_ID; }

    @Override
    public String getDisplayType() { return "JIT Business ID Mapper"; }

    @Override
    public String getHelpText() { return "Appelle le service utilisateur pour récupérer le businessId si absent"; }

    @Override
    public String getDisplayCategory() { return "Token Mapper"; }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
                            KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx) {

        UserModel user = userSession.getUser();
        String bId = user.getFirstAttribute("businessId");

        if (bId == null || bId.isEmpty()) {
            bId = fetchFromUserService(user.getId());
            if (bId != null) {
                user.setSingleAttribute("businessId", bId);
            }
        }

        String claimName = mappingModel.getConfig().get(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME);
        token.getOtherClaims().put(claimName != null ? claimName : "businessId", bId);
    }

    private String fetchFromUserService(String keycloakId) {
        String serviceUrl = "http://host.docker.internal:8082/user-service/profile/resolve/" + keycloakId;
        String secret = "REDACTED-INTERNAL-SECRET";

        log.info(">>> JIT Mapper: Tentative d'appel vers " + serviceUrl);

        try {
            URL url = new URL(serviceUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setRequestProperty("X-Internal-Secret", secret);

            int responseCode = conn.getResponseCode();
            System.out.println(">>> JIT Mapper: Code réponse = " + responseCode);

            if (responseCode == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String inputLine;
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }

                    String result = response.toString().trim().replace("\"", "");
                    System.out.println(">>> JIT Mapper: ID récupéré et nettoyé = " + result);
                    return result.isEmpty() ? null : result;
                }
            } else  {
                throw new RuntimeException("Erreur lors de l'appel au service utilisateur");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
