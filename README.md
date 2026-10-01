# wely-identity

Configuration Keycloak de la plateforme [Wely Calendar](https://github.com/WelyLabs/wely-platform) : export du realm, thème, et un **plugin Java custom** qui fait le pont entre l'identité Keycloak et l'identité métier.

---

## Le problème résolu

Keycloak connaît un utilisateur par son UUID d'IdP. L'application le connaît par un UUID métier stocké en PostgreSQL. Trois options se présentaient :

| Option | Inconvénient |
|---|---|
| Chaque service traduit l'UUID à chaque requête | Un appel réseau supplémentaire sur **chaque** requête de **chaque** service |
| Un webhook Keycloak à la création d'utilisateur | Fragile, asynchrone, et muet sur les utilisateurs créés hors application (console admin, fédération) |
| **Injecter l'identifiant métier dans le token** | Retenu |

Un **mapper de protocole Keycloak** enrichit le token à l'émission. Les services lisent `jwt.getClaimAsString("businessId")` et n'ont plus jamais à traduire quoi que ce soit.

---

## `BusinessIdMapper`

Plugin Java déployé dans Keycloak (`plugins/business-id-mapper/`), enregistré comme `ProtocolMapper` via le SPI standard.

```mermaid
sequenceDiagram
    participant U as Utilisateur
    participant KC as Keycloak
    participant M as BusinessIdMapper
    participant API as wely-users
    participant DB as PostgreSQL

    U->>KC: authentification
    KC->>M: émission du token
    M->>KC: attribut "businessId" présent ?

    alt déjà résolu
        KC-->>M: businessId (cache)
    else première connexion
        M->>API: GET /user-service/profile/resolve/{keycloakId}<br/>X-Internal-Secret
        API->>DB: recherche par keycloak_id
        alt inconnu
            API->>DB: création + hashtag unique
            API->>API: publie USER_CREATED sur Kafka
        end
        API-->>M: businessId
        M->>KC: mémorise l'attribut sur l'utilisateur
    end

    M-->>KC: claim businessId
    KC-->>U: access token
```

### Deux propriétés notables

**Le provisioning est paresseux (JIT).** L'utilisateur métier est créé au moment de la première émission de token, pas avant. Aucun batch de synchronisation, aucun webhook : tout utilisateur capable d'obtenir un token existe forcément côté application, quelle que soit la façon dont son compte Keycloak a été créé.

**La résolution n'a lieu qu'une fois.** Le résultat est mémorisé comme attribut utilisateur Keycloak ; les émissions suivantes lisent le cache sans appel réseau. Le coût est donc d'un seul appel HTTP dans la vie d'un compte.

### Découverte de l'URL

```java
String baseUrl = System.getenv("USERS_API_URL");
if (baseUrl == null || baseUrl.isBlank()) {
    baseUrl = isLocalDev ? "http://host.docker.internal:8082"   // Docker Compose
                         : "http://wely-users-service:8082";     // Kubernetes
}
```

La variable d'environnement est injectée par Kubernetes ; le repli couvre le développement local.

---

## Contenu du dépôt

```
├── wely-realm.json          export du realm (clients, rôles, flows, mappers)
├── Dockerfile                       image Keycloak + plugin + thème
├── plugins/
│   └── business-id-mapper/          plugin Java (Maven)
│       └── src/main/
│           ├── java/com/calendar/BusinessIdMapper.java
│           └── resources/META-INF/services/
│               └── org.keycloak.protocol.ProtocolMapper    ← enregistrement SPI
├── themes/                          thème de connexion personnalisé
├── MAPPER_CONFIGURATION.md          configuration du mapper dans la console
└── CI-CD-PLAN.md                    stratégie de livraison
```

---

## Build

```bash
cd plugins/business-id-mapper
./mvnw package                       # → target/keycloak-1.0-SNAPSHOT.jar
```

Le `Dockerfile` construit une image Keycloak avec le JAR déposé dans `/opt/keycloak/providers/` et le thème dans `/opt/keycloak/themes/`.

---

## Démarrage local

Depuis la racine du projet :

```bash
docker compose up -d
```

Keycloak démarre sur `:8080` en `start-dev`, avec import automatique du realm et stockage `dev-file` local — aucun risque de toucher à une base distante.

---

## Configuration du realm

Le realm `wely-web` définit :

- le client public **`wely-web`** utilisé par le frontend (flow OIDC) ;
- le client confidentiel **`wely-users-api-client`** (`client_credentials`) permettant à `wely-users` d'appeler l'Admin API ;
- le mapper **`businessId`** attaché au client frontend, qui injecte le claim.

Voir [`MAPPER_CONFIGURATION.md`](MAPPER_CONFIGURATION.md) pour la procédure de configuration dans la console.

### Secret du client confidentiel

L'export de realm porte `"secret": "CHANGE_ME_AT_IMPORT"` pour `wely-users-api-client` : un secret réel n'a pas sa place dans un fichier versionné. Après import, définir la vraie valeur — elle doit correspondre à `KEYCLOAK_CLIENT_SECRET` côté `wely-users` :

```bash
kcadm.sh update clients/$(kcadm.sh get clients -r wely-web \
    -q clientId=wely-users-api-client --fields id --format csv --noquotes) \
  -r wely-web -s secret="$KEYCLOAK_CLIENT_SECRET"
```

En local, le realm est initialisé par `keycloak-init.sql` (overlay `local` du dépôt d'infra), qui utilise une valeur factice.

---

## Limites connues

- **Le secret du client confidentiel doit être réinjecté après import** : l'export porte volontairement `CHANGE_ME_AT_IMPORT` (voir ci-dessus). Un secret réel figure encore dans l'historique Git de ce dépôt et doit être révoqué.
- **Appel HTTP bloquant** dans le mapper (`HttpURLConnection`, timeout 3 s) — acceptable puisque Keycloak n'est pas réactif et que l'appel n'a lieu qu'une fois par compte, mais il ajoute une dépendance dure : si `wely-users` est indisponible lors d'une première connexion, le token est émis sans `businessId`.
- **Journalisation via `System.out`** plutôt qu'un logger.
