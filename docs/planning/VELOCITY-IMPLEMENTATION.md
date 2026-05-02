TL;DR Add an opt-in proxy flow so players can run:
/replay play <name> — play locally (existing behavior)
/replay play <name> <server> — transfer to <server> via a Velocity proxy and view the replay there

The origin backend will send a plugin-message JSON payload on a configurable channel (default replay:control) to the proxy. The proxy connects the player to the target server and forwards the same payload to the destination backend. The destination backend stores a pending auto-start keyed by player UUID and, when the player joins, automatically calls ReplayManager.startReplay(replayName, player) (if configured to do so). Existing local playback remains unchanged.

Overview

Goal: allow viewing a replay on a different backend server via a Velocity proxy while keeping the plugin’s public API unchanged.

High-level components:
- Config: Proxy.Enabled, Proxy.Mode, Proxy.Channel, Proxy.AutoStartOnJoin, Proxy.TransferTimeoutSeconds
- Backend messenger: ProxyMessenger interface and VelocityPluginMessageMessenger implementation
- Command change: /replay play <name> [<server>] (positional server arg)
- Incoming handler: IncomingProxyMessageHandler to accept forwarded payloads and auto-start on player join
- Minimal Velocity proxy plugin (example): receives the backend message, validates, connects the player, forwards the payload to the destination backend

Plugin configuration (keys & defaults)

- Proxy.Enabled: false
- Proxy.Mode: "none" (options: "none", "velocity")
- Proxy.Channel: "replay:control"
- Proxy.AutoStartOnJoin: true
- Proxy.TransferTimeoutSeconds: 10

Plugin-message payload (recommended JSON)

Example shape (JSON):
- action: "transfer"
- playerUuid: string
- playerName: string
- targetServer: string
- replayName: string | null
- optional: nonce: string
- optional: hmac: string

Rationale: JSON is easy to serialize with Gson (already present) and is straightforward to debug. The hmac/nonce fields are optional for increased security.

Backend implementation (server-side)

1. Add config keys and ReplayConfigSetting entries
   - Update src/main/resources/plugin.yml to document the Proxy block and (optionally) declare plugin channels if desired.
   - Add entries in src/main/java/me/justindevb/replay/config/ReplayConfigSetting.java so code can read the new keys via existing helpers.

2. Add ProxyMessenger abstraction
   - New interface: me.justindevb.replay.proxy.ProxyMessenger
     - Method: void sendPlayerTransfer(Player player, String targetServer, @Nullable String replayName);
   - New implementation: me.justindevb.replay.proxy.VelocityPluginMessageMessenger
     - Reads Proxy.Channel from config
     - Serializes the transfer payload to JSON (use Gson)
     - Sends plugin-message bytes to proxy using Bukkit plugin messaging API (send via the requesting player)
     - Defensive logging on failure

3. Wire messenger into Replay main plugin
   - Add field: private ProxyMessenger proxyMessenger;
   - Add getter: public @Nullable ProxyMessenger getProxyMessenger()
   - In onEnable(): if Proxy.Enabled && Proxy.Mode == "velocity", instantiate VelocityPluginMessageMessenger(this) and register IncomingProxyMessageHandler (see below). Otherwise leave proxyMessenger null.
   - Clean up on onDisable().

4. Update ReplayCommand to accept server arg
   - Support /replay play <name> (local) and /replay play <name> <server> (transfer).
   - Parsing: if args.length >= 3, treat the last token as server; replayName = join(args[1..n-1]).
   - If server provided and replay.getProxyMessenger() != null:
     - Call replay.getProxyMessenger().sendPlayerTransfer(player, server, replayName)
     - Send player feedback: "Transferring you to <server> to watch '<replayname>'..."
     - Do not call local replayManager.startReplay(...)
   - Else: use the existing local start logic

Incoming plugin-message handler & auto-start logic

New class: me.justindevb.replay.proxy.IncomingProxyMessageHandler
- Listens for plugin-messages on Proxy.Channel
- On receiving a transfer payload (action == "transfer"):
  - Parse playerUuid and replayName
  - Insert pending auto-start: ConcurrentHashMap<UUID, Pending> with expiry after Proxy.TransferTimeoutSeconds
  - Schedule expiry cleanup via plugin scheduler
- Player join listener:
  - On player join, if pending entry exists for UUID:
    - If Proxy.AutoStartOnJoin is true: schedule main-thread call to replay.getReplayManager().startReplay(replayName, player)
    - Else: message player "Replay '<replayname>' is ready — run `/replay play <replayName>` to start."
    - Remove pending entry

Requirements:
- Use ConcurrentHashMap for thread safety
- Ensure Bukkit API calls run on server/main thread (use scheduler if the plugin-message callback is off-thread)

Velocity proxy example (minimal)

Purpose: small Velocity plugin that runs on the proxy and handles moving players and forwarding payloads.

Responsibilities:
- Receive plugin-message (from origin backend) on configured channel
- Validate the request (recommended: allowlist or HMAC)
- Find target player on proxy (by UUID/username)
- Request connection to destination server: player.createConnectionRequest(targetServer).connect()
- After connecting (or immediately), forward the same JSON payload to the destination backend via Velocity's outgoing plugin-message API so that the destination backend's IncomingProxyMessageHandler receives it before/when player joins

Security: proxy must be trusted; prefer proxy-side validation. Optionally use HMAC/nonce in the payload and validate it on the proxy.

Tests and docs

Unit tests:
- Update src/test/java/me/justindevb/replay/ReplayCommandTest.java to include:
  - play_withServerAndProxy_invokesProxyMessenger_and_doesNotStartLocalReplay() — mock ProxyMessenger and ReplayManager
  - Keep existing local-play tests intact

Documentation updates:
- Add docs/VELOCITY_INTEGRATION.md (this doc)
- Update README.md to describe /replay play <name> [<server>] and link to the new doc
- Update docs/API.md to mention that ReplayManager.startReplay remains the canonical API and proxy flow is opt-in
- Add CHANGELOG.md entry under ## [Unreleased] -> Added

Security & operational considerations

- The proxy is the trusted component: enforce validation at the proxy (whitelist or HMAC)
- If you control both proxy and backends, using a shared secret/HMAC with nonce is recommended to prevent spoofing
- Document channel registration in plugin.yml if your deployment requires static registration
- This design uses a ProxyMessenger abstraction to make it straightforward to add other transports later (Bungee, Redis, etc.)

Integration checklist (manual verification steps)

1. Add Proxy.* config and enable proxy mode on origin backend
2. Deploy minimal Velocity proxy plugin (example) to proxy; ensure it listens on channel Proxy.Channel
3. On origin backend run /replay play <name> <server> — plugin should send plugin-message to proxy and report transfer
4. Proxy should connect player to target server and forward payload to target backend
5. Target backend should receive payload, store pending auto-start, and start replay on player join

Change list (files to create / edit)
- Create:
  - docs/VELOCITY_INTEGRATION.md (this content)
  - src/main/java/me/justindevb/replay/proxy/ProxyMessenger.java (interface)
  - src/main/java/me/justindevb/replay/proxy/VelocityPluginMessageMessenger.java (impl)
  - src/main/java/me/justindevb/replay/proxy/IncomingProxyMessageHandler.java (impl)
- Edit:
  - src/main/java/me/justindevb/replay/Replay.java (add messenger field/getter and registration)
  - src/main/java/me/justindevb/replay/ReplayCommand.java (update play parsing & flow)
  - src/main/java/me/justindevb/replay/config/ReplayConfigSetting.java (add new keys)
  - src/main/resources/plugin.yml (document/configure Proxy.*, optionally register channel)
  - src/test/java/me/justindevb/replay/ReplayCommandTest.java (add tests)
  - README.md, docs/API.md, CHANGELOG.md (docs updates)
