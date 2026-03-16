const express = require('express');
const fs = require('fs');
const path = require('path');

const app = express();
app.use(express.json({ limit: '1mb' }));

const PORT = process.env.PORT || 3000;
const DB_PATH = path.join(__dirname, '..', 'data', 'device-status.json');
const OFFLINE_WINDOW_MS = Number(process.env.OFFLINE_WINDOW_MS || 120000);

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
  const ageMs = now - (deviceRecord.last_seen_millis || 0);
  const stale = ageMs > OFFLINE_WINDOW_MS;
  const internetActive = !!deviceRecord.last_status?.internet_active;

  return {
    ...deviceRecord,
    computed_status: {
      status_source: "heartbeat",
      online: !stale && internetActive,
      internet_active_last_report: internetActive,
      stale,
      age_ms: ageMs,
      offline_window_ms: OFFLINE_WINDOW_MS
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
  const lastSeen = body.sent_at_millis || Date.now();

  db.devices[deviceId] = {
    device_id: deviceId,
    source: body.source || 'NotifyLink',
    reason: body.reason || 'unknown',
    device: body.device || {},
    last_status: body.status || {},
    last_seen_millis: lastSeen,
    last_seen_iso: new Date(lastSeen).toISOString(),
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
