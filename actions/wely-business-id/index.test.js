const { test, beforeEach, afterEach, mock } = require('node:test');
const assert = require('node:assert/strict');
const { onExecutePostLogin } = require('./index');

const BUSINESS_ID = '3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b';
const USERS_URL = 'https://dev-web.welylabs.app/api/v1/user-service';

function makeEvent(overrides = {}) {
  return {
    resource_server: { identifier: 'wely-api-dev' },
    secrets: { USERS_URL_DEV: USERS_URL, INTERNAL_SECRET_DEV: 's3cret' },
    authentication: {
      methods: [
        { name: 'pwd', timestamp: '2026-11-16T10:00:00.000Z' },
        { name: 'mfa', timestamp: '2026-11-16T10:05:00.000Z' },
      ],
    },
    user: {
      user_id: 'auth0|abc',
      username: 'theo',
      email: 'theo@example.com',
      given_name: 'Theo',
      family_name: 'B',
      app_metadata: {},
    },
    ...overrides,
  };
}

function makeApi() {
  return {
    access: { deny: mock.fn() },
    user: { setAppMetadata: mock.fn() },
    accessToken: { setCustomClaim: mock.fn() },
    idToken: { setCustomClaim: mock.fn() },
  };
}

const claims = (token) => Object.fromEntries(token.setCustomClaim.mock.calls.map((c) => c.arguments));

beforeEach(() => {
  mock.method(console, 'log', () => {});
});

afterEach(() => {
  mock.restoreAll();
});

test('uses the cached businessId without calling wely-users', async () => {
  const fetch = mock.method(globalThis, 'fetch');
  const event = makeEvent();
  event.user.app_metadata = { businessIds: { dev: BUSINESS_ID } };
  const api = makeApi();

  await onExecutePostLogin(event, api);

  assert.equal(fetch.mock.callCount(), 0);
  assert.equal(api.user.setAppMetadata.mock.callCount(), 0);
  assert.deepEqual(claims(api.accessToken), {
    businessId: BUSINESS_ID,
    preferred_username: 'theo',
    authenticatedAt: Date.parse('2026-11-16T10:05:00.000Z') / 1000,
  });
  assert.deepEqual(claims(api.idToken), { businessId: BUSINESS_ID });
});

test('provisions the user on first login and caches the id per environment', async () => {
  const fetch = mock.method(globalThis, 'fetch', async () => new Response(BUSINESS_ID, { status: 200 }));
  const event = makeEvent();
  event.user.app_metadata = { businessIds: { prod: 'other' } };
  const api = makeApi();

  await onExecutePostLogin(event, api);

  const [url, init] = fetch.mock.calls[0].arguments;
  assert.equal(url, `${USERS_URL}/profile/provision`);
  assert.equal(init.method, 'POST');
  assert.equal(init.headers['X-Internal-Secret'], 's3cret');
  assert.ok(init.signal instanceof AbortSignal);
  assert.deepEqual(JSON.parse(init.body), {
    subjectId: 'auth0|abc', username: 'theo', firstName: 'Theo', lastName: 'B',
  });
  assert.deepEqual(api.user.setAppMetadata.mock.calls[0].arguments,
    ['businessIds', { prod: 'other', dev: BUSINESS_ID }]);
  assert.equal(claims(api.accessToken).businessId, BUSINESS_ID);
  assert.equal(claims(api.idToken).businessId, BUSINESS_ID);
  assert.equal(api.access.deny.mock.callCount(), 0);
});

test('falls back to the email local part and null names when the profile lacks them', async () => {
  const fetch = mock.method(globalThis, 'fetch', async () => new Response(`"${BUSINESS_ID}"`, { status: 200 }));
  const event = makeEvent();
  delete event.user.username;
  delete event.user.given_name;
  delete event.user.family_name;
  const api = makeApi();

  await onExecutePostLogin(event, api);

  assert.deepEqual(JSON.parse(fetch.mock.calls[0].arguments[1].body), {
    subjectId: 'auth0|abc', username: 'theo', firstName: null, lastName: null,
  });
  assert.equal(claims(api.accessToken).businessId, BUSINESS_ID);
});

test('denies the login when wely-users is down', async () => {
  mock.method(globalThis, 'fetch', async () => { throw new TypeError('fetch failed'); });
  const api = makeApi();

  await onExecutePostLogin(makeEvent(), api);

  assert.equal(api.access.deny.mock.callCount(), 1);
  assert.match(api.access.deny.mock.calls[0].arguments[0], /Réessayez/);
  assert.equal(api.user.setAppMetadata.mock.callCount(), 0);
  assert.equal(api.accessToken.setCustomClaim.mock.callCount(), 0);
});

test('denies the login when wely-users answers an error or a malformed id', async () => {
  for (const response of [new Response('nope', { status: 503 }), new Response('not-a-uuid', { status: 200 })]) {
    mock.method(globalThis, 'fetch', async () => response);
    const api = makeApi();

    await onExecutePostLogin(makeEvent(), api);

    assert.equal(api.access.deny.mock.callCount(), 1);
    assert.equal(api.accessToken.setCustomClaim.mock.callCount(), 0);
  }
});

test('denies the login when the environment secrets are missing', async () => {
  const fetch = mock.method(globalThis, 'fetch');
  const api = makeApi();

  await onExecutePostLogin(makeEvent({ secrets: {} }), api);

  assert.equal(fetch.mock.callCount(), 0);
  assert.equal(api.access.deny.mock.callCount(), 1);
});

test('refuses an audience that does not name an environment', async () => {
  for (const resource_server of [undefined, { identifier: 'wely-api' }, { identifier: 'https://example/api/v2/' }]) {
    const fetch = mock.method(globalThis, 'fetch');
    const api = makeApi();

    await onExecutePostLogin(makeEvent({ resource_server }), api);

    assert.equal(api.access.deny.mock.callCount(), 1);
    assert.equal(fetch.mock.callCount(), 0);
    assert.equal(api.accessToken.setCustomClaim.mock.callCount(), 0);
  }
});

test('omits authenticatedAt when no authentication method is reported', async () => {
  const event = makeEvent({ authentication: { methods: [] } });
  event.user.app_metadata = { businessIds: { dev: BUSINESS_ID } };
  const api = makeApi();

  await onExecutePostLogin(event, api);

  assert.deepEqual(claims(api.accessToken), { businessId: BUSINESS_ID, preferred_username: 'theo' });
});

test('coerces a social profile to a valid wely username', async () => {
  const cases = [
    [{ email: 'jean.dupont@gmail.com' }, 'jean_dupont'],
    [{ nickname: 'octo-cat' }, 'octo-cat'],
    [{ nickname: 'jo' }, 'jo_'],
    [{ email: 'élodie+wely@x.fr' }, '_lodie_wely'],
    [{ nickname: 'a'.repeat(40) }, 'a'.repeat(30)],
    [{}, 'user'],
  ];
  for (const [profile, expected] of cases) {
    const fetch = mock.method(globalThis, 'fetch', async () => new Response(BUSINESS_ID, { status: 200 }));
    const event = makeEvent();
    event.user = { user_id: 'google-oauth2|1', app_metadata: {}, ...profile };
    const api = makeApi();

    await onExecutePostLogin(event, api);

    assert.equal(JSON.parse(fetch.mock.calls[0].arguments[1].body).username, expected, JSON.stringify(profile));
    assert.equal(claims(api.accessToken).preferred_username, expected);
    mock.restoreAll();
  }
});
