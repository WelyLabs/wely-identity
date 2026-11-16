// Auth0 post-login Action: puts the wely-users businessId (and authenticatedAt) into the tokens.

const AUDIENCE_PATTERN = /^wely-api-([a-z]+)$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const PROVISION_TIMEOUT_MS = 5000;
const UNAVAILABLE_MESSAGE =
  "Nous n'avons pas pu créer votre compte pour le moment. Réessayez dans quelques instants.";

exports.onExecutePostLogin = async (event, api) => {
  // The audience names the environment; anything else is refused, never guessed.
  const match = AUDIENCE_PATTERN.exec(event.resource_server?.identifier ?? '');
  if (!match) {
    api.access.deny('This application must request an audience named wely-api-<env>.');
    return;
  }
  const env = match[1];

  // app_metadata is shared by every environment of the tenant, so ids are kept per env.
  const businessIds = event.user.app_metadata?.businessIds ?? {};
  let businessId = businessIds[env];

  if (!businessId) {
    try {
      businessId = await provision(event, env);
    } catch (error) {
      console.log(`wely-users (${env}) could not provision ${event.user.user_id}: ${error.message}`);
      api.access.deny(UNAVAILABLE_MESSAGE);
      return;
    }
    api.user.setAppMetadata('businessIds', { ...businessIds, [env]: businessId });
  }

  api.accessToken.setCustomClaim('businessId', businessId);
  api.idToken.setCustomClaim('businessId', businessId);
  // wely-chat and wely-events sign messages and events with it; Keycloak put it in every access token.
  const username = usernameOf(event.user);
  if (username) {
    api.accessToken.setCustomClaim('preferred_username', username);
  }

  const authenticatedAt = latestAuthentication(event.authentication?.methods ?? []);
  if (authenticatedAt !== undefined) {
    api.accessToken.setCustomClaim('authenticatedAt', authenticatedAt);
  }
};

async function provision(event, env) {
  const url = event.secrets[`USERS_URL_${env.toUpperCase()}`];
  const secret = event.secrets[`INTERNAL_SECRET_${env.toUpperCase()}`];
  if (!url || !secret) {
    throw new Error(`USERS_URL_${env.toUpperCase()} or INTERNAL_SECRET_${env.toUpperCase()} is not set`);
  }

  const { user } = event;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), PROVISION_TIMEOUT_MS);
  try {
    const response = await fetch(`${url}/profile/provision`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Internal-Secret': secret },
      body: JSON.stringify({
        subjectId: user.user_id,
        username: usernameOf(user),
        firstName: user.given_name ?? null,
        lastName: user.family_name ?? null,
      }),
      signal: controller.signal,
    });
    if (response.status !== 200) {
      throw new Error(`answered ${response.status}`);
    }
    // The endpoint returns a bare string; strip quotes in case it is ever serialised as JSON.
    const businessId = (await response.text()).trim().replaceAll('"', '');
    if (!UUID_PATTERN.test(businessId)) {
      throw new Error(`returned a malformed business id: '${businessId}'`);
    }
    return businessId;
  } finally {
    clearTimeout(timer);
  }
}

// wely-users accepts 3 to 30 of [a-zA-Z0-9_-]. Social users have no username: GitHub gives a
// nickname (its login, the only name when the email is private), Google an email such as
// jean.dupont@gmail.com. Whatever comes in is coerced to the rule; the hashtag tells duplicates apart.
const USERNAME_MIN = 3;
const USERNAME_MAX = 30;

function usernameOf(user) {
  const raw = user.username ?? user.nickname ?? user.email?.split('@')[0] ?? '';
  const cleaned = raw.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, USERNAME_MAX);
  return cleaned ? cleaned.padEnd(USERNAME_MIN, '_') : 'user';
}

// Epoch seconds of the most recent authentication method, or undefined when there is none.
function latestAuthentication(methods) {
  const times = methods.map((m) => Date.parse(m.timestamp)).filter((t) => !Number.isNaN(t));
  return times.length ? Math.floor(Math.max(...times) / 1000) : undefined;
}
