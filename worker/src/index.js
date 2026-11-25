const APP_ID = 'nl.niek.protect_fast';
const encoder = new TextEncoder();

let cachedToken = '';
let tokenExpiresAt = 0;

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

export default {
  async fetch(request, env) {
    const receivedAt = Date.now();
    if (new URL(request.url).pathname !== '/push') return new Response('Not found', { status: 404 });
    if (request.method !== 'POST') return new Response('Method not allowed', { status: 405 });

    const body = await request.json().catch(() => null);
    if (!body || typeof body !== 'object' || Array.isArray(body)) {
      return Response.json({ error: 'Expected a JSON object' }, { status: 400 });
    }

    const pushToken = typeof body.push_token === 'string' ? body.push_token.trim() : '';
    if (!pushToken) return Response.json({ error: 'Missing push_token' }, { status: 400 });

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
