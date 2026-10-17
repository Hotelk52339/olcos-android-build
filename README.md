# olcOS for Android

The Android client of olcOS: the same Go core as the iOS app (olcrtc rooms,
whole-server bonding, the Xray engine), the same design and the same
features, built as a native Android app (Kotlin, Jetpack Compose).

## Layout

- `app/` — the application. `app/libs/olcoscore.aar` is the Go core bound with
  gomobile; CI builds it, it is not committed.
- `scripts/build-core.sh` — binds the core: the olcrtc `mobile` package, the
  tun2socks glue (`tunstack`), the bonding client (`bond`) and the olcos Xray
  engine, from the same `gomobile/` wrapper module the iOS app uses.
- `.github/workflows/ci.yml` — builds the core and a debug APK on every push;
  the APK is the run's artifact.

The Go sources live with the iOS app for now (`gomobile/` in olcos-ios-dev,
published as the olcos-ios-build snapshot); CI checks that snapshot out next
to this repository. Moving them to olcos-core is the next step.

## Getting connections

Paste on the Connections tab, or open the link from another app:

- an `olcrtc://` link of one room;
- a device invite made on the iPhone app (`olcrtc-sub://host:port/token?enc=v1#key`):
  the server's every connection, with its extra rooms, the other protocols
  joined into the whole-server connection and the bond endpoint. The list is
  encrypted with the key from the link; the phone keeps the key and refreshes
  the list, by hand or after a failed connect;
- a subscription link (`olcrtc-sub://` or `https://`) or the text of a list
  (the upstream sub.md format with the olcOS fields `##id`, `##lanes`,
  `##with`, `##bond`).

## Managing a server

A device invite made on the iPhone can also carry server access (its list
has `#agent*` fields: the agent's address, the pinned certificate, the API
prefix, a one-time code and the role). On import the app makes a P-256 key in
the Android keystore, redeems the code with the server agent (`POST pair`)
and from then on signs every request with that key, the way the iOS app does.
The server page shows the agent's status and follows its events; it starts,
stops and restarts rooms and reads their logs. An owner also changes rooms,
adds and removes protocols and rotates the key: the same scripts the iOS app
runs over SSH (`app/src/main/assets/scripts/` holds copies of
`add-carrier.sh` and `rotate-key.sh`; keep them identical to the iOS ones),
run through the agent's `exec`.

## Installing a server from the app

Connections → "+" → "My own server (VPS)" (the first-run card offers it
too). The app signs in to the VPS over SSH (a password or a pasted private
key; the host key is trusted on the first connection and remembered, a
changed one is refused), uploads `srv.sh` and the core patch series and runs
them under `nohup`, following the log until the exit file appears, exactly
the sequence the iOS app and `install.sh` run: podman, the pinned core built
in the Go image, the server in the chosen room. Then it builds the agent on
the server from the Go sources it carries, initialises it with this phone as
the owner, stores the status scripts and starts the systemd unit. What comes
back is saved as a connection and a managed server with its SSH login kept,
so the server page can reinstall the agent later. `srv.sh`, the status
scripts and the agent sources are copied into the assets by CI from the core
snapshot (`app/src/main/assets/scripts/srv.sh`, `scripts/agent-status/`,
`agent/`); they are not committed. Telemost, VK, SaluteJazz and WB Stream
rooms are made from the phone (the same flows as the server page) or pasted.

## Sessions

- One room listens on the proxy port. Several rooms each get a loopback port
  and are joined on the proxy port: by the bond client when the server runs a
  bond endpoint (every connection over all rooms at once), else by a balancer
  that hands each new connection to the next room that is up. A room that
  drops is joined again with a growing wait.
- A session counts as connected only once one HTTPS request has come back
  through the proxy port.

## Modes

- **Proxy** — a SOCKS5 listener on 127.0.0.1 for apps that take one.
- **VPN** — the whole device through `VpnService`: the core's sockets are
  protected from the tunnel, the packets go through tun2socks into the core's
  loopback listener, UDP DNS is answered with "truncated" so the resolver
  retries over TCP. The device's routes are taken only after the session has
  carried a request.
