const APP_ID = 'nl.niek.doorbellcompanion';
const CAMERA_ENTITY_RE = /^camera\.[a-z0-9_]+$/;
const APNS_TOKEN_RE = /^(sandbox|production):((?:[a-fA-F0-9]{2}){16,128})$/;
const encoder = new TextEncoder();

let cachedToken = '';
let tokenExpiresAt = 0;
const apnsTokens = new Map();

const b64url = (data) =>
  btoa(String.fromCharCode(...(typeof data === 'string' ? encoder.encode(data) : new Uint8Array(data))))
    .replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');

function serverTimingHeader(prefix, authStart, sendStart, cacheHit) {
  if (sendStart === undefined) {
    return `${prefix}_auth;dur=${Math.round(performance.now() - authStart)};desc="error"`;
  }
  const authDur = Math.round(sendStart - authStart);
  const sendDur = Math.round(performance.now() - sendStart);
  return `${prefix}_auth;dur=${authDur};desc="${cacheHit ? 'hit' : 'miss'}", ${prefix}_send;dur=${sendDur}`;
}

function validateDoorbellPush(body, expectedAppId) {
  if (body.registration_info?.app_id !== expectedAppId) {
    return { error: Response.json({ error: 'Unsupported app_id' }, { status: 403 }) };
  }
  const data = body.data;
  if (
    !data ||
    typeof data !== 'object' ||
    data.type !== 'doorbell_ring' ||
    typeof data.camera_entity_id !== 'string' ||
    !CAMERA_ENTITY_RE.test(data.camera_entity_id)
  ) {
    return { error: Response.json({ error: 'Invalid doorbell call payload' }, { status: 400 }) };
  }
  return {
    cameraEntityId: data.camera_entity_id,
    doorbellName: String(data.doorbell_name || body.title || 'Doorbell').slice(0, 100),
    eventMs: data.event_ms ? String(data.event_ms) : undefined,
  };
}

async function getAccessToken({ client_email, private_key }) {
  if (cachedToken && Date.now() < tokenExpiresAt - 60_000) {
    return { token: cachedToken, cacheHit: true };
  }

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
  return { token: cachedToken, cacheHit: false };
}

async function getAPNSToken({ team_id, key_id, private_key }) {
  const now = Math.floor(Date.now() / 1000);
  const cacheKey = `${team_id}:${key_id}`;
  const cached = apnsTokens.get(cacheKey);
  if (cached && now - cached.createdAt < 45 * 60) {
    return { token: cached.token, cacheHit: true };
  }

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
  return { token, cacheHit: false };
}

async function sendIOS(body, pushToken, env, receivedAt) {
  const topic = env.APNS_BUNDLE_ID || APP_ID;
  const validated = validateDoorbellPush(body, topic);
  if (validated.error) return validated.error;

  const token = APNS_TOKEN_RE.exec(pushToken);
  if (!token) return Response.json({ error: 'Invalid iOS push_token' }, { status: 400 });

  const sandbox = token[1] === 'sandbox';
  const config = (sandbox ? env.APNS_SANDBOX_AUTH : env.APNS_PRODUCTION_AUTH) || env.APNS_AUTH;
  if (!config) return Response.json({ error: 'APNs is not configured for this environment' }, { status: 503 });

  const authStart = performance.now();
  let sendStart;
  let cacheHit = false;
  const pushResponse = (payload, status) => Response.json(payload, {
    status,
    headers: { 'Server-Timing': serverTimingHeader('apns', authStart, sendStart, cacheHit) },
  });

  try {
    const auth = await getAPNSToken(JSON.parse(config));
    cacheHit = auth.cacheHit;
    sendStart = performance.now();
    const host = sandbox ? 'api.sandbox.push.apple.com' : 'api.push.apple.com';
    const response = await fetch(`https://${host}/3/device/${token[2]}`, {
      method: 'POST',
      headers: {
        authorization: `bearer ${auth.token}`,
        'content-type': 'application/json',
        'apns-topic': `${topic}.voip`,
        'apns-push-type': 'voip',
        'apns-priority': '10',
        'apns-expiration': '0',
      },
      body: JSON.stringify({
        aps: {},
        call_id: crypto.randomUUID(),
        camera_entity_id: validated.cameraEntityId,
        doorbell_name: validated.doorbellName,
        received_at: receivedAt,
        ...(validated.eventMs && { event_ms: validated.eventMs }),
      }),
    });
    if (response.ok) return pushResponse({ success: true }, 200);
    const reason = (await response.json().catch(() => null))?.reason;
    console.error('APNs VoIP send failed', response.status, reason);
  } catch (error) {
    console.error('APNs VoIP send failed', error.name);
  }
  return pushResponse({ error: 'APNs send failed' }, 502);
}

async function sendAndroid(body, pushToken, env, receivedAt) {
  const validated = validateDoorbellPush(body, APP_ID);
  if (validated.error) return validated.error;

  const fcmData = {
    type: 'doorbell_ring',
    received_at: String(receivedAt),
    camera_entity_id: validated.cameraEntityId,
    doorbell_name: validated.doorbellName,
    ...(validated.eventMs && { event_ms: validated.eventMs }),
  };

  const authStart = performance.now();
  let sendStart;
  let cacheHit = false;
  const pushResponse = (payload, status) => Response.json(payload, {
    status,
    headers: { 'Server-Timing': serverTimingHeader('fcm', authStart, sendStart, cacheHit) },
  });

  try {
    const serviceAccount = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT);
    const auth = await getAccessToken(serviceAccount);
    cacheHit = auth.cacheHit;
    sendStart = performance.now();
    const res = await fetch(
      `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(serviceAccount.project_id)}/messages:send`,
      {
        method: 'POST',
        headers: { Authorization: `Bearer ${auth.token}`, 'Content-Type': 'application/json' },
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
      return pushResponse({ error: 'Firebase send failed' }, 502);
    }
    return pushResponse({ success: true }, 200);
  } catch (error) {
    console.error('Push relay failed', error);
    return pushResponse({ error: 'Firebase send failed' }, 502);
  }
}

export default {
  async fetch(request, env) {
    const receivedAt = Date.now();
    const { pathname } = new URL(request.url);
    if ((pathname === '/ios' || pathname === '/android') && request.method === 'GET') {
      const platform = pathname === '/ios' ? 'iOS' : 'Android';
      return new Response(
        `<!doctype html><html><head><title>Doorbell Companion ${platform}</title>`
        + '<link rel="redirect_uri" href="doorbell-companion://auth-callback">'
        + `</head><body>Doorbell Companion for ${platform}</body></html>`,
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

    if (pathname === '/push/ios') return sendIOS(body, pushToken, env, receivedAt);
    return sendAndroid(body, pushToken, env, receivedAt);
  },
};
