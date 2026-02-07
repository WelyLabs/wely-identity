# Configuration du BusinessIdMapper dans Keycloak

## Problème Identifié

Le plugin `BusinessIdMapper` est bien chargé par Keycloak, mais il n'est **pas configuré** dans le client `calendar-app-client`. C'est pourquoi il n'est jamais appelé lors de la génération des tokens.

## Solution : Ajouter le Mapper au Client

### Option 1 : Via l'Interface Admin Keycloak (Recommandé pour tester)

1. Connecte-toi à l'admin Keycloak : `https://auth.welylabs.app/admin`
2. Sélectionne le realm `calendar-app`
3. Va dans **Clients** → `calendar-app-client`
4. Onglet **Client scopes** → Clique sur le scope dédié (ex: `calendar-app-client-dedicated`)
5. Onglet **Mappers** → **Add mapper** → **By configuration**
6. Sélectionne **JIT Business ID Mapper**
7. Configure :
   - **Name** : `business-id-mapper`
   - **Token Claim Name** : `businessId`
   - **Add to ID token** : ON
   - **Add to access token** : ON
   - **Add to userinfo** : ON
8. Sauvegarde

### Option 2 : Via Export/Import (Pour automatiser)

Ajoute cette configuration dans ton fichier `calendar-app-realm.json` :

```json
{
  "protocolMappers": [
    {
      "name": "business-id-mapper",
      "protocol": "openid-connect",
      "protocolMapper": "jit-business-id-mapper",
      "consentRequired": false,
      "config": {
        "claim.name": "businessId",
        "id.token.claim": "true",
        "access.token.claim": "true",
        "userinfo.token.claim": "true"
      }
    }
  ]
}
```

## Vérification

Une fois configuré, tu devrais voir dans les logs Keycloak :

```
>>> JIT Mapper: Environment=PROD, URL=http://wely-users-service:8082/users/profile/resolve/[keycloak-id]
```

Et le token JWT devrait contenir le claim `businessId`.
