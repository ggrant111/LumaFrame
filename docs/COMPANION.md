# Luma Frame Companion · Android v1.2

Lovable project: https://lovable.dev/projects/a4318ed3-fb43-45d7-8bed-c0a358ea22bf

## Connect the two apps

1. Install LumaFrame v1.2 on an Android 9+ phone or tablet.
2. Create your account on the companion website (https://luma-frame.lovable.app). New accounts get their own household.
3. On the frame, tap **Cloud** → **Pair device**. The companion address is built in; nothing to type. (Advanced lets you change it if you host your own companion.)
4. Connect the frame in one of three ways:
   - **Scan the QR code** with your phone, sign in (email or Google) and tap **Connect this frame**.
   - **Sign in on this frame** with your email and password. Used once over HTTPS, never stored. If you own several households you pick one.
   - Or enter the shown code on the website under **Devices → Pair device**.
   Only household owners can connect frames.
5. Upload images into albums, connect/select calendars, then assign those albums and calendars to this frame.
6. Set clock, calendar, and agenda visibility, corner positions, timezone, glass opacity, interval, and photo fit for the frame. The device fetches a new snapshot approximately every minute while cloud mode is open.

Cloud mode is independent of local mode. It does not need Android's READ_CALENDAR permission. Google account authorization happens on the website. The local Android calendar and local photo list are not uploaded. Use **Settings → Local frame mode** to return to them.

## Layout behavior

Large screens display the four configured corner areas. If two widgets share a corner, they stack in a scrollable column. On narrow screens (under 700 dp), the corner groups stack in order for readability. Each group is scrollable when its content exceeds available space. `photo_fit` supports `cover` (crop) and `contain` (fit). The cloud mode uses tinted translucent gradient panels; the local mode retains its sampled frosted-photo effect.

## Permissions and storage

The website owns account and household membership, photo/calendar assignments, and busy-only calendar redaction. Android holds only a device-scoped opaque token, encrypted with Android Keystore AES-GCM. Cloud photos and snapshot data are in the app's private directory; screenshots are blocked in cloud mode. No user password, OAuth refresh token, or service-role key is stored on the frame. The optional on-frame sign-in sends the password once to `frame-login` and discards it.

The cache is capped at 250 MiB, with 20 MiB per downloaded image. Photos that fail validation or exceed these limits are skipped and reported in the status line. Their URLs are not persisted. Saved cloud content expires after 24 hours without a successful sync; the app stops displaying it and clears its cache when running again. Remote revocation clears device credentials and cloud data when the next sync returns 401/403. A disconnected device cannot receive immediate revocation; offline expiry is the fallback. Unpair locally also clears the private cache. If unpaired while offline, revoke the old frame from the website too.

## Shared device API

All requests are HTTPS POST with JSON. The endpoint base is `https://<backend>/functions/v1`.

- `frame-pair-begin`: `{device_name, platform:"android", app_version:"1.1.0"}` → `{pairing_id,pairing_secret,user_code,expires_at,poll_interval_seconds}`.
- `frame-pair-poll`: `{pairing_id,pairing_secret}` → `{status:"pending"|"expired"}` or `{status:"paired",device_id,device_token,device_name}`.
- `frame-sync`: `Authorization: Bearer <device_token>`, `{app_version:"1.1.0"}` → complete snapshot below.
- `frame-unpair`: same authorization, `{}` → `{success:true}`.
- `frame-login` (v1.2, optional): `{pairing_id,pairing_secret,email,password,household_id?}` → `{status:"paired",device_id,device_token,device_name}` | `{status:"choose_household",households:[{id,name}]}` | `{status:"expired"}`; errors 401 invalid_credentials, 403 not_owner.
- QR pairing page: `https://<host>/pair?code=<user_code>`.

Snapshot schema version 1:

```json
{
  "schema_version": 1,
  "revision": "opaque",
  "server_time": "2026-10-29T12:00:00Z",
  "device_name": "Kitchen frame",
  "settings": {
    "interval_seconds": 15,
    "photo_fit": "cover",
    "timezone": "America/Denver",
    "clock_visible": true,
    "calendar_visible": true,
    "agenda_visible": true,
    "clock_position": "top-left",
    "calendar_position": "bottom-left",
    "agenda_position": "bottom-right",
    "glass_opacity": 0.65
  },
  "photos": [{"id":"opaque", "version":"opaque", "url":"https://short-lived-signed-url"}],
  "events": [{"id":"opaque", "title":"Busy", "start_ms":1790000000000, "end_ms":1790003600000, "all_day":false, "location":""}],
  "sync_after_seconds": 60
}
```

All-day events use UTC-midnight date boundaries. Timed events render in the configured frame timezone. Snapshot event IDs are never treated as Android Calendar Provider IDs. The endpoint must return only currently assigned content and must redact busy-only events before transmission. The Android client refuses non-HTTPS API addresses, redirects, unsupported schema versions, oversized JSON and invalid images; 429 responses honor a bounded Retry-After interval.

## Service setup

Google Calendar connection needs a Google OAuth application and server-side credentials configured in Lovable. Do not paste secrets into chat or commit them to Git. Public calendar refresh depends on the companion backend implementation and its scheduler. See the companion project's OAuth and API documentation for current setup and validation status.
