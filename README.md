# wely-identity

Auth0 configuration code for [Wely Calendar](https://github.com/WelyLabs/wely-platform). Today that is one
post-login Action, `actions/wely-business-id/`, which puts the application identity into every token.

## What the Action does

Auth0 knows a user as `auth0|<id>`; the application knows them by a UUID owned by `wely-users`. The Action
bridges the two at login, so services keep reading `jwt.getClaimAsString("businessId")` and never translate.

1. **Environment from the audience.** The requested API must be `wely-api-<env>`; anything else (no
   audience, the Management API...) is denied, never guessed — the same rule as the old Keycloak realm.
2. **Cached id.** `app_metadata.businessIds.<env>` is used when present: logins after the first never call
   the cluster.
3. **First login.** Otherwise it calls `wely-users`, which is idempotent on `subjectId`:

   ```
   POST ${USERS_URL_<ENV>}/profile/provision
   X-Internal-Secret: ${INTERNAL_SECRET_<ENV>}
   { "subjectId": "auth0|...", "username": "...", "firstName": null|"...", "lastName": null|"..." }
   → 200 text/plain <businessId UUID>
   ```

   `username` falls back to the email's local part. Timeout 5 s. On success the id is stored in
   `app_metadata`; on any failure the login is denied with a "try again" message. No account gets a token
   without a Wely user.
4. **Claims.** `businessId` on the access and ID tokens; `authenticatedAt` (epoch seconds of the most recent
   authentication method) on the access token, read by `wely-users` before account deletion — Auth0 does
   not put `auth_time` in access tokens and Actions may not set it.

### `app_metadata` layout

```json
{ "businessIds": { "dev": "<uuid>", "prod": "<uuid>" } }
```

There is a single free tenant for every environment, so `app_metadata` is shared by all of them. Keying
the id by environment keeps a dev login from ever handing out a prod id, and the reverse.

## Deploying

1. Auth0 dashboard → **Actions → Library → Create Action → Build from scratch**, trigger *Login /
   Post Login*, runtime Node 22, name `wely-business-id`.
2. Paste `actions/wely-business-id/index.js`.
3. **Secrets** (type them in the dashboard; they are never committed):
   - `USERS_URL_DEV` = `https://dev-web.welylabs.app/api/v1/user-service`
   - `INTERNAL_SECRET_DEV` = the `X-Internal-Secret` of `wely-users` dev
   - later, `USERS_URL_PROD` / `INTERNAL_SECRET_PROD`
4. **Deploy**, then **Actions → Triggers → post-login**: drag the Action into the flow and **Apply**.

## Tenant layout

One tenant, `dev-8k6vch1dvh1qxrdz.eu.auth0.com` (EU); environments are split by a `-dev` / `-prod`
suffix on every object.

| Object | dev |
|---|---|
| API | `wely-api-dev` (RS256, offline access) |
| Database connection | `wely-dev-users` (username required) |
| SPA | `wely-web-dev` |
| Native | `wely-ios-dev` |
| M2M (Management API, for `wely-users`) | `wely-users-dev` |
| Action | `wely-business-id` (post-login) |

Clients must send `audience=wely-api-<env>` on `/authorize`, or the Action denies the login.

## Tests

```bash
node --test
```

Node's built-in runner, no dependency: `fetch` and the Auth0 `api` object are mocked.
