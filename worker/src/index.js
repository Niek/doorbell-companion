const APP_ID = 'nl.niek.protect_fast';
const IOS_APP_ID = 'nl.niek.protect-fast';
const encoder = new TextEncoder();

let cachedToken = '';
let tokenExpiresAt = 0;
const apnsTokens = new Map();

const b64url = (data) =>
  btoa(String.fromCharCode(...(typeof data === 'string' ? encoder.encode(data) : new Uint8Array(data))))
    .replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');

async function getAccessToken({ client_email, private_key }) {
  if (cachedToken && Date.now() < tokenExpiresAt - 60_000) return cachedToken;

  const now = Math.floor(Date.now() / 1000);
  const unsigned = [
    { alg: 'RS256', typ: 'JWT' },
    {
      iss: client_email,
      scope: 'https://www.googleapis.com/auth/firebase.messaging',
      aud: 'https://oauth2.googleapis.com/token',
      iat: now,
      exp: now + 3600,
    },
  ].map((part) => b64url(JSON.stringify(part))).join('.');

  const pem = private_key.replace(/-----[^-]+-----|\s/g, '');
  const key = await crypto.subtle.importKey(
    'pkcs8',
    Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, encoder.encode(unsigned));

  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: `${unsigned}.${b64url(signature)}`,
    }),
  });
  if (!res.ok) throw new Error(`Google authentication failed: ${res.status}`);

  const { access_token, expires_in } = await res.json();
  cachedToken = access_token;
  tokenExpiresAt = Date.now() + expires_in * 1000;
  return cachedToken;
}

async function getAPNSToken({ team_id, key_id, private_key }) {
  const now = Math.floor(Date.now() / 1000);
  const cacheKey = `${team_id}:${key_id}`;
  const cached = apnsTokens.get(cacheKey);
  if (cached && now - cached.createdAt < 45 * 60) return cached.token;

  const unsigned = [
    { alg: 'ES256', kid: key_id },
    { iss: team_id, iat: now },
  ].map((part) => b64url(JSON.stringify(part))).join('.');
  const pem = private_key.replace(/-----[^-]+-----|\s/g, '');
  const key = await crypto.subtle.importKey(
    'pkcs8',
    Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)),
    { name: 'ECDSA', namedCurve: 'P-256' },
    false,
    ['sign'],
  );
  const signature = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' }, key, encoder.encode(unsigned),
  );
  const token = `${unsigned}.${b64url(signature)}`;
  apnsTokens.set(cacheKey, { token, createdAt: now });
  return token;
}

async function sendIOS(body, env, receivedAt) {
  const topic = env.APNS_BUNDLE_ID || IOS_APP_ID;
  if (body.registration_info?.app_id !== topic) {
    return Response.json({ error: 'Unsupported app_id' }, { status: 403 });
  }
  const token = /^(sandbox|production):((?:[a-fA-F0-9]{2}){16,128})$/.exec(body.push_token.trim());
  const data = body.data;
  if (!token || data?.type !== 'doorbell_ring'
      || typeof data.camera_entity_id !== 'string'
      || !/^camera\.[a-z0-9_]+$/.test(data.camera_entity_id)) {
    return Response.json({ error: 'Invalid iOS doorbell call' }, { status: 400 });
  }
  const sandbox = token[1] === 'sandbox';
  const config = (sandbox ? env.APNS_SANDBOX_AUTH : env.APNS_PRODUCTION_AUTH) || env.APNS_AUTH;
  if (!config) return Response.json({ error: 'APNs is not configured for this environment' }, { status: 503 });

  try {
    const auth = await getAPNSToken(JSON.parse(config));
    const host = sandbox ? 'api.sandbox.push.apple.com' : 'api.push.apple.com';
    const response = await fetch(`https://${host}/3/device/${token[2]}`, {
      method: 'POST',
      headers: {
        authorization: `bearer ${auth}`,
        'content-type': 'application/json',
        'apns-topic': `${topic}.voip`,
        'apns-push-type': 'voip',
        'apns-priority': '10',
        'apns-expiration': '0',
      },
      body: JSON.stringify({
        aps: {},
        call_id: crypto.randomUUID(),
        camera_entity_id: data.camera_entity_id,
        doorbell_name: String(data.doorbell_name || body.title || 'Doorbell').slice(0, 100),
        received_at: receivedAt,
      }),
    });
    if (response.ok) return Response.json({ success: true });
    const reason = (await response.json().catch(() => null))?.reason;
    console.error('APNs VoIP send failed', response.status, reason);
  } catch (error) {
    console.error('APNs VoIP send failed', error.name);
  }
  return Response.json({ error: 'APNs send failed' }, { status: 502 });
}

export default {
  async fetch(request, env) {
    const receivedAt = Date.now();
    const { pathname } = new URL(request.url);
    if (pathname === '/ios' && request.method === 'GET') {
      return new Response(
        '<!doctype html><html><head><title>Protect Fast iOS</title>'
        + '<link rel="redirect_uri" href="protect-fast://auth-callback">'
        + '</head><body>Protect Fast for iOS</body></html>',
        { headers: { 'content-type': 'text/html; charset=utf-8' } },
      );
    }
    if (pathname !== '/push' && pathname !== '/push/ios') {
      return new Response('Not found', { status: 404 });
    }
    if (request.method !== 'POST') return new Response('Method not allowed', { status: 405 });

    const body = await request.json().catch(() => null);
    if (!body || typeof body !== 'object' || Array.isArray(body)) {
      return Response.json({ error: 'Expected a JSON object' }, { status: 400 });
    }

    const pushToken = typeof body.push_token === 'string' ? body.push_token.trim() : '';
    if (!pushToken) return Response.json({ error: 'Missing push_token' }, { status: 400 });
    if (pathname === '/push/ios') return sendIOS(body, env, receivedAt);

    const appId = body.registration_info?.app_id;
    if (appId && appId !== APP_ID) {
      return Response.json({ error: 'Unsupported app_id' }, { status: 403 });
    }

    const extra = body.data && typeof body.data === 'object' ? body.data : {};
    const fcmData = {
      type: String(extra.type || 'doorbell_ring'),
      received_at: String(receivedAt),
      camera_entity_id: String(extra.camera_entity_id || extra.entity_id || ''),
      doorbell_name: String(extra.doorbell_name || body.title || 'Doorbell'),
      ...(extra.event_ms && { event_ms: String(extra.event_ms) }),
    };

    try {
      const serviceAccount = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT);
      const token = await getAccessToken(serviceAccount);
      const res = await fetch(
        `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(serviceAccount.project_id)}/messages:send`,
        {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({
            message: {
              token: pushToken,
              data: fcmData,
              android: { priority: 'HIGH', ttl: '30s' },
            },
          }),
        },
      );
      if (!res.ok) {
        console.error('FCM /push send failed', res.status, await res.text());
        return Response.json({ error: 'Firebase send failed' }, { status: 502 });
      }
      return Response.json({ success: true });
    } catch (error) {
      console.error('Push relay failed', error);
      return Response.json({ error: 'Firebase send failed' }, { status: 502 });
    }
  },
};
