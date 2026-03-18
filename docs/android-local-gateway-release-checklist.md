# Android Local Gateway Release Checklist (Phase F)

This checklist is for the `zah-gateway-local` Android-local gateway fork.

## Security

- [ ] Secrets stored in encrypted storage (`SecurePrefs`) where possible
- [ ] Telegram bot token is masked in API responses and UI
- [ ] Local token endpoint restricted to loopback-only access
- [ ] Protected `/v1/*` endpoints require Bearer auth (local token or OAuth-lite token)
- [ ] No secrets printed in notifications/logs

## Runtime/Resilience

- [ ] Foreground service starts reliably at app launch
- [ ] Boot receiver restores gateway service on reboot
- [ ] Telegram polling intent persists across restarts
- [ ] Watchdog recovers server/poll worker if thread dies
- [ ] Backoff strategy applied on polling/network errors

## UX

- [ ] Connect tab has one-tap local gateway setup
- [ ] Telegram quick setup + send test available in UI
- [ ] User-facing status text for success/failure

## Verification

- [ ] `GET /health` returns OK
- [ ] `GET /status` shows running gateway and readiness flags
- [ ] `POST /v1/setup/quickstart` configures Telegram + starts polling
- [ ] Telegram `/start`, `/status`, `/help` handled with replies
- [ ] App restart preserves token/config and returns to healthy state

## Packaging

- [ ] Commit on `zah-gateway-local`
- [ ] PR updated with current phase notes
- [ ] Release artifact uploaded (tagged `gateway-local-spike-v*`)
- [ ] Release notes include known limitations vs full upstream gateway
