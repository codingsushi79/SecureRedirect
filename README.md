# SecureRedirect Setup Guide

This guide explains how to install and configure the **SecureRedirect** Paper plugin so players can be securely redirected from one server ("main") to another ("dest") using transfer packets and cookies.

> Recommended Paper version: **1.21.11** (or later with `Player#transfer` support)

---

<details>
<summary><strong>1. Install the plugin</strong></summary>

### 1.1. Build or download the JAR

If you are using this repo directly:

1. Run:
   ```bash
   gradle build
   ```
2. The plugin JAR will be at:
   - `build/libs/SecureRedirect-1.0.2.jar`

Copy that JAR to each Paper server that should participate in redirects.

### 1.2. Drop the plugin into each server

For **each** Paper server (main and dest):

1. Stop the server if it is running.
2. Place `SecureRedirect-1.0.2.jar` into the `plugins/` folder.
3. Start the server once so it generates `plugins/SecureRedirect/config.yml`.

</details>

---

<details>
<summary><strong>2. Configure the main (source) server</strong></summary>

This is the server where players join normally and run `/redirect`.

Edit `plugins/SecureRedirect/config.yml` on the **main** server.

### 2.1. Basic redirect target

```yaml
# Where to send players when using /redirect on MAIN
target-host: "your-dest-host"   # e.g. "hello.exaroton.me"
# Optional. If omitted or invalid, defaults to 25565.
# target-port: 25565

# Will be filled by /redirect gen-hash
send-hash: ""

# Not required on MAIN, can be left empty
receive-hash: ""

# Players can join MAIN directly
require-transfer: false
```

Notes:
- If your destination uses the **default port 25565**, you can omit `target-port` entirely.
- If your destination uses a custom port (e.g. `25566`), set it explicitly:
  ```yaml
  target-port: 25566
  ```

### 2.2. Origin ID (optional)

```yaml
# Identifier this server uses when redirecting players
origin-id: "main"

# On MAIN you typically allow redirects from anywhere, so this list can stay empty
allowed-origins: []
```

You only need to care about `origin-id` and `allowed-origins` if you want to restrict which servers are allowed to redirect **into** another server (see the dest config section).

### 2.3. Generate the send-hash

On the **main** server, in-game as an OP or from the console:

```text
/redirect gen-hash
```

This will:
- Generate a random hex hash.
- Save it into `send-hash` in `config.yml`.
- Print the value in chat / console.

Copy this value; you will paste it into the **dest** server `receive-hash`.

</details>

---

<details>
<summary><strong>3. Configure the destination server</strong></summary>

This is the server that players are redirected TO.

### 3.1. Enable transfers in server.properties

Edit `server.properties` on the **dest** server:

```properties
accepts-transfers=true
```

Then **fully restart** the dest server.

### 3.2. Basic config.yml

Edit `plugins/SecureRedirect/config.yml` on the **dest** server:

```yaml
# target-host/target-port are only needed if DEST will redirect players onward.
# For a simple two-server setup you can leave them as defaults.
target-host: "127.0.0.1"
# Optional. If omitted or invalid, defaults to 25565.
# target-port: 25565

# Optional on DEST unless you want it to send players further.
send-hash: ""

# IMPORTANT: must match MAIN's send-hash
receive-hash: "<paste value from /redirect gen-hash on MAIN>"

# Identifier this server uses when redirecting players
origin-id: "dest"

# Which origin servers are allowed to redirect into DEST.
# Make sure this list contains MAIN's origin-id.
allowed-origins:
  - "main"

# Block direct joins; only transfers are allowed
require-transfer: true

kick-no-transfer-message: "You must join this server via secure redirect."

kick-bad-hash-message: "Invalid or missing redirect token."
```

### 3.3. Restart after editing

After changing `config.yml` on DEST, **restart the server** (or at least reload the plugin) so the new values are applied.

</details>

---

<details>
<summary><strong>4. Using the plugin</strong></summary>

### 4.1. Redirect yourself

On the **main** server, as a player with permission:

```text
/redirect
```

This will:
- Attach a cookie to you containing `<origin-id>:<send-hash>` from MAIN.
- Use Mojang's transfer packet to move you to `target-host:target-port`.

### 4.2. Redirect another player

```text
/redirect <player>
```

Permissions:
- `secureredirect.redirect` – use `/redirect` on yourself.
- `secureredirect.redirect.others` – use `/redirect <player>`.
- `secureredirect.genhash` – use `/redirect gen-hash`.

### 4.3. Rotating the token

Any time you want to change the secret token:

1. On MAIN, run:
   ```text
   /redirect gen-hash
   ```
2. Copy the new `send-hash` value.
3. On DEST, update `receive-hash` in `config.yml` to this new value.
4. Restart DEST so it reads the new config.

</details>

---

<details>
<summary><strong>5. Advanced: origin-id and allowed-origins</strong></summary>

The plugin encodes the redirect token as:

```text
<origin-id>:<send-hash>
```

On DEST, when a player joins via transfer, the plugin:

1. Verifies `player.isTransferred()`.
2. Reads and parses the cookie into `origin-id` and `hash`.
3. Checks that `hash` matches `receive-hash`.
4. If `allowed-origins` is **non-empty**, checks that `origin-id` is listed there.

This lets you restrict which servers are allowed to redirect into DEST.

**Example multi-origin setup:**

- MAIN server:
  ```yaml
  origin-id: "main"
  allowed-origins: []
  ```

- LOBBY server:
  ```yaml
  origin-id: "lobby"
  allowed-origins: []
  ```

- DEST server:
  ```yaml
  origin-id: "dest"
  allowed-origins:
    - "main"
    - "lobby"
  ```

Now only `main` and `lobby` can send players into `dest` (provided their hashes also match `receive-hash`).

</details>

---

<details>
<summary><strong>6. Troubleshooting</strong></summary>

### 6.1. Stuck on "Transferring to a new server..."

If the client never finishes transferring and no errors appear in the console:

- Check on the **DEST** server:
  - `accepts-transfers=true` is set in `server.properties`.
  - The server is actually Paper 1.21.x (not Spigot or a proxy binary).
- Check on the **MAIN** server:
  - `target-host` and `target-port` point to the correct DEST address.
- Watch the DEST console while running `/redirect`:
  - If no login/join messages appear, the client is not reaching that server (host/port/host limitations).

### 6.2. Direct join still works on DEST

- Ensure on DEST `config.yml`:
  ```yaml
  require-transfer: true
  ```
- Restart DEST after changing the config.
- Make sure `SecureRedirect` is listed as enabled in `/plugins`.

### 6.3. "Invalid or missing redirect token"

- Confirm that on DEST:
  - `receive-hash` exactly matches MAIN's `send-hash`.
- Confirm that on DEST:
  - `allowed-origins` contains MAIN's `origin-id` (or is empty to allow any).

</details>
