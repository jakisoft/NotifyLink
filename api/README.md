# NotifyLink Device Status API (ExpressJS)

API ini menerima status device dari aplikasi NotifyLink lalu menyediakan endpoint untuk cek apakah device terlihat online/offline.

## Menjalankan API

```bash
cd api
npm install
npm start
```

Default port: `3000` (ubah dengan env `PORT`).

## Endpoint

### 1) Health check
`GET /health`

### 2) Terima status device dari aplikasi
`POST /api/device/status`

Contoh payload dari app:

```json
{
  "source": "NotifyLink",
  "event": "device_status",
  "sent_at_millis": 1710000000000,
  "reason": "notification_event",
  "device": {
    "device_id": "abc123",
    "manufacturer": "Xiaomi",
    "brand": "Redmi",
    "model": "Note 12",
    "device": "sunstone",
    "android_version": "14",
    "sdk_int": 34
  },
  "status": {
    "internet_active": true,
    "network_type": "wifi",
    "pending_notification_queue": 2,
    "master_on": true,
    "telegram_on": true,
    "webhook_on": true
  }
}
```

### 3) Cek status satu device
`GET /api/device/:deviceId/status`

Response berisi `computed_status`:
- `online`: true/false
- `stale`: data terlalu lama atau tidak
- `age_ms`: umur update terakhir
- `offline_window_ms`: batas stale (default 120000 ms)

### 4) List semua device
`GET /api/devices`

## Integrasi di aplikasi Android
Isi field **Device Status API** dengan URL endpoint POST, misalnya:

`http://YOUR_SERVER:3000/api/device/status`
