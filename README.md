# wely-identity

Auth0 configuration code for [Wely Calendar](https://github.com/WelyLabs/wely-platform): a post-login Action,
`actions/wely-business-id/`, which puts the application identity into every token, and the Universal Login
screen, `universal-login/`.

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
   not put `auth_time` in access tokens and Actions may not set it; `preferred_username` on the access token,
   which `wely-chat` and `wely-events` sign messages and events with.

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

## The login and sign-up screen

`universal-login/` carries the screen the user lands on, ported from the Keycloak theme it replaces:
brand panel on the OKLCH gradient to the left, the form to the right on the application's warm canvas,
three colour fields drifting behind it, the demo account under the sign-in form. The application already
sends people here — `AuthService.login()` and `AuthService.signup()` (`screen_hint=signup`) — so there is
no page to write in `wely-web`.

**The work is split in two, and the split is the point.** Auth0 renders the form itself, and its markup
carries hashed class names that change without notice, so nothing styles its inside:

| File | Owns | Applied through |
|---|---|---|
| `page-template.liquid` | everything around the form: layout, brand panel, canvas, background, footer | `branding/templates/universal-login` |
| `theme.json` | the form itself: colours, Outfit, 12px radius, field and button style | `branding/themes` |

### Deploying

With the [Auth0 CLI](https://github.com/auth0/auth0-cli), logged in to the tenant:

```bash
auth0 ul templates update --template-file universal-login/page-template.liquid
auth0 api patch "branding/themes/$(auth0 api get branding/themes/default --json | jq -r .themeId)" \
  --data "$(cat universal-login/theme.json)"
```

If no theme exists yet, `auth0 api post branding/themes --data "$(cat universal-login/theme.json)"` creates it.
The theme alone is also editable by hand in **Branding → Universal Login → Customization**; the template is not.

### What this needs from the tenant

The page template endpoint wants a **custom domain**, and on a free tenant it answers `402 Payment Required`
until a card is on file. The theme has no such condition. So on a bare free tenant you get the typeface, the
colours, the radius and the canvas, and the form stays centred with no brand panel — which is the degraded
form of this design, not a different one.

### Known limits

- **The button is flat blue, not the gradient.** A theme carries one colour per role, and the form's inner
  markup is not ours to select. The gradient stays on the brand panel.
- **The fonts come from jsDelivr.** The Keycloak theme bundled them; an Auth0 page has nowhere to host a file.
  `font-display: swap` and the system fallback mean a CDN outage costs the typeface, not the login.
- **French only**, like the theme before it. The form's own wording follows the tenant's languages.

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
