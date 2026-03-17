const express = require('express');
const fs = require('fs');
const path = require('path');

const app = express();
app.use(express.json({ limit: '1mb' }));

const PORT = process.env.PORT || 3000;
const DB_PATH = path.join(__dirname, '..', 'data', 'device-status.json');
const OFFLINE_WINDOW_MS = Number(process.env.OFFLINE_WINDOW_MS || 30000);
const HEARTBEAT_GRACE_MS = Number(process.env.HEARTBEAT_GRACE_MS || 15000);
const MAX_CLIENT_CLOCK_SKEW_MS = Number(process.env.MAX_CLIENT_CLOCK_SKEW_MS || 10 * 60 * 1000);

function resolveEffectiveOfflineWindowMs(deviceRecord) {
  const reportedHeartbeatInterval = Number(deviceRecord.last_status?.heartbeat_interval_ms || 0);
  if (!Number.isFinite(reportedHeartbeatInterval) || reportedHeartbeatInterval <= 0) {
    return OFFLINE_WINDOW_MS;
  }

  // Heartbeat yang datang tepat 30 detik sering dianggap telat karena jitter jaringan.
  // Pakai minimal 2x interval + grace agar status tidak mudah "flapping".
  return Math.max(OFFLINE_WINDOW_MS, (reportedHeartbeatInterval * 2) + HEARTBEAT_GRACE_MS);
}

function resolveLastSeenMillis(sentAtMillis, receivedAtMillis) {
  const sentAt = Number(sentAtMillis);
  if (!Number.isFinite(sentAt) || sentAt <= 0) {
    return receivedAtMillis;
  }

  const clockSkew = Math.abs(receivedAtMillis - sentAt);
  if (clockSkew > MAX_CLIENT_CLOCK_SKEW_MS) {
    return receivedAtMillis;
  }

  return sentAt;
}

function readDb() {
  try {
    const raw = fs.readFileSync(DB_PATH, 'utf8');
    return JSON.parse(raw);
  } catch (e) {
    return { devices: {} };
  }
}

function writeDb(db) {
  fs.mkdirSync(path.dirname(DB_PATH), { recursive: true });
  fs.writeFileSync(DB_PATH, JSON.stringify(db, null, 2), 'utf8');
}

function buildDeviceResponse(deviceRecord) {
  const now = Date.now();
  const referenceMillis = deviceRecord.last_seen_server_millis || deviceRecord.last_seen_millis || 0;
  const ageMs = now - referenceMillis;
  const effectiveOfflineWindowMs = resolveEffectiveOfflineWindowMs(deviceRecord);
  const stale = ageMs > effectiveOfflineWindowMs;
  const hasInternetFlag = typeof deviceRecord.last_status?.internet_active === 'boolean';
  const internetActive = hasInternetFlag ? deviceRecord.last_status.internet_active : true;

  return {
    ...deviceRecord,
    computed_status: {
      status_source: "heartbeat",
      online: !stale && internetActive,
      internet_active_last_report: hasInternetFlag ? internetActive : null,
      stale,
      age_ms: ageMs,
      offline_window_ms: effectiveOfflineWindowMs,
      configured_offline_window_ms: OFFLINE_WINDOW_MS
    }
  };
}

app.get('/health', (req, res) => {
  res.json({ ok: true, service: 'notifylink-device-status-api', now: Date.now() });
});

app.post('/api/device/status', (req, res) => {
  const body = req.body || {};
  const deviceId = body.device?.device_id;

  if (!deviceId) {
    return res.status(400).json({ ok: false, error: 'device.device_id is required' });
  }

  const db = readDb();
  const receivedAt = Date.now();
  const lastSeen = resolveLastSeenMillis(body.sent_at_millis, receivedAt);

  db.devices[deviceId] = {
    device_id: deviceId,
    source: body.source || 'NotifyLink',
    reason: body.reason || 'unknown',
    device: body.device || {},
    last_status: body.status || {},
    last_seen_millis: lastSeen,
    last_seen_server_millis: receivedAt,
    last_seen_iso: new Date(lastSeen).toISOString(),
    last_seen_server_iso: new Date(receivedAt).toISOString(),
    updated_at_iso: new Date().toISOString()
  };

  writeDb(db);
  return res.json({ ok: true, device_id: deviceId, stored_at: Date.now() });
});

app.get('/api/device/:deviceId/status', (req, res) => {
  const db = readDb();
  const record = db.devices[req.params.deviceId];

  if (!record) {
    return res.status(404).json({ ok: false, error: 'device not found', device_id: req.params.deviceId });
  }

  return res.json({ ok: true, data: buildDeviceResponse(record) });
});

app.get('/api/devices', (req, res) => {
  const db = readDb();
  const items = Object.values(db.devices || {}).map(buildDeviceResponse);
  res.json({ ok: true, total: items.length, data: items });
});

app.listen(PORT, () => {
  console.log(`NotifyLink device status API listening on :${PORT}`);
});
