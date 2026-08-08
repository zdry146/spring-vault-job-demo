# Vault Agent Sidecar — Minimal Setup for VM Batch Jobs

[Vault Agent](https://developer.hashicorp.com/vault/docs/agent-and-proxy/agent)
is a long-running daemon that lives next to your batch job on the VM.
It handles the full credential lifecycle automatically:

```
+-----------------------------+        +-----------------------------+
|  Vault Agent (sidecar)      |  HTTPS | HashiCorp Vault             |
|  - AppRole auth             | <----> |  - auth/approle             |
|  - auto-renew tokens        |        |  - database/creds/<role>    |
|  - render secrets to files  |        |                             |
+-----------------------------+        +-----------------------------+
        |
        | writes / reads files on local disk
        v
+-----------------------------+
|  Spring Boot batch job      |
|  - reads secret from disk   |
|  - never sees Vault directly|
+-----------------------------+
```

The job's only contract with Vault is: **a file on disk exists with the
right contents.** The job does not need to know about AppRole, wrapping
tokens, lease renewals, or any of that. Vault Agent handles all of it.

---

## When to use this vs. other patterns

| Scenario | Recommended pattern |
|---|---|
| Job runtime << lease (short batch, < 30 min) | **Direct AppRole** (current demo default) |
| Job runtime >= lease, no Vault Agent possible | **Pattern B** in `docs/lifecycle.md` |
| VM with sidecar / agent slot available | **Vault Agent** (this doc) |
| Kubernetes pod | Vault Agent Injector sidecar via annotations |
| AWS Lambda / Fargate | IAM auth + Vault Agent AWS auth mode |

---

## 1. Install Vault (binary)

Pick the install method that matches your VM distro. HashiCorp officially
packages for Debian / Ubuntu / RHEL / Amazon Linux; otherwise download
the binary from the [releases page](https://releases.hashicorp.com/vault/).

```bash
# Debian / Ubuntu (official repo)
curl -fsSL https://apt.releases.hashicorp.com/gpg | sudo gpg --dearmor -o /usr/share/keyrings/hashicorp-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/hashicorp.list
sudo apt update && sudo apt install vault

# Verify
vault version
```

## 2. Place the AppRole role-id on disk

The `role_id` is long-lived and not a secret in the strict sense. Put it
on disk where Vault Agent can read it:

```bash
sudo install -d -m 0700 -o vault -g vault /etc/vault
echo "716cbb29-fc91-a4dd-b772-8713d5b3b37f" | sudo tee /etc/vault/role-id >/dev/null
sudo chmod 0644 /etc/vault/role-id
```

Get the value with:

```bash
vault read -field=role_id auth/approle/role/<your-role>/role-id
```

## 3. Bootstrap the first secret-id

`secret_id` is the actual secret. Get it **once** from the Vault server
using an operator token (admin task, not part of the VM lifecycle):

```bash
# On an admin machine with VAULT_TOKEN set:
vault write -f auth/approle/role/<your-role>/secret-id | tee /tmp/secret-id.json
SECRET_ID=$(jq -r .data.secret_id /tmp/secret-id.json)
rm /tmp/secret-id.json

# On the target VM (secure channel):
sudo install -m 0600 -o vault -g vault /dev/null /etc/vault/secret-id
echo "$SECRET_ID" | sudo tee /etc/vault/secret-id >/dev/null
sudo chmod 0600 /etc/vault/secret-id
```

Vault Agent will then **renew this secret_id on its own** by calling
`auth/approle/role/<role>/secret-id` again — your VM never needs a new
manual bootstrap unless the role's policy changes.

## 4. Write the Vault Agent config

`/etc/vault/vault-agent.hcl`:

```hcl
# Vault Agent config for a Spring Boot batch job.
# Auto-auths with AppRole, renders DB credentials as a JSON file the job reads.

exit_after_auth = false
pid_file         = "/var/run/vault-agent.pid"

auto_auth {
  method "approle" {
    config = {
      role_id_file_path   = "/etc/vault/role-id"
      secret_id_file_path = "/etc/vault/secret-id"
    }
  }

  # Where the resulting Vault token is written. Job does not need this,
  # but it's useful for debugging / manual `vault` CLI use.
  sink "file" {
      config = {
          path = "/etc/vault/.vault-token"
      }
  }
}

# Render the DB credential every time the lease / token state changes.
template {
  destination = "/run/vault/db-creds.json"
  perms       = "0600"
  error_on_missing_key = true

  contents = <<EOH
{{ with secret "database/creds/mydb-role" }}
{
  "username": {{ .Data.username | toJSON }},
  "password": {{ .Data.password | toJSON }},
  "lease_id": {{ .LeaseID | toJSON }},
  "ttl":      {{ .LeaseDuration }}
}
{{ end }}
EOH
}
```

Notable knobs:

- `exit_after_auth = false` — keep the agent alive to renew leases / tokens
- `error_on_missing_key = true` — fail loudly if Vault response shape changes
- `/run/vault/db-creds.json` — JSON file the job reads (mode 600)
- `database/creds/mydb-role` — must match the role configured in
  `scripts/vault-setup.sh`

## 5. Run as a systemd unit

`/etc/systemd/system/vault-agent.service`:

```ini
[Unit]
Description=HashiCorp Vault Agent (sidecar for batch job)
Documentation=https://developer.hashicorp.com/vault/docs/agent-and-proxy/agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=vault
Group=vault
ExecStart=/usr/bin/vault agent -config=/etc/vault/vault-agent.hcl
ExecReload=/bin/kill -HUP $MAINPID
KillMode=process
Restart=on-failure
RestartSec=5
LimitNOFILE=65536

# Hardening
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
ReadWritePaths=/etc/vault /run/vault /var/log/vault-agent
CapabilityBoundingSet=

[Install]
WantedBy=multi-user.target
```

Enable + start:

```bash
sudo useradd --system --home /etc/vault --shell /usr/sbin/nologin vault
sudo install -d -m 0755 -o vault -g vault /var/log/vault-agent
sudo install -d -m 0755 -o vault -g vault /run/vault
sudo systemctl daemon-reload
sudo systemctl enable --now vault-agent
sudo systemctl status vault-agent   # should be active (running)
sudo journalctl -u vault-agent -f   # tail logs
```

## 6. Verify

```bash
# 1. Token file appears (sink writes it once authenticated)
sudo ls -l /etc/vault/.vault-token
# -rw------- 1 vault vault  56 ... .vault-token

# 2. Rendered secret file appears after a few seconds
sudo ls -l /run/vault/db-creds.json
# -rw------- 1 vault vault 200 ... db-creds.json

# 3. Contents look sane
sudo cat /run/vault/db-creds.json
# {"username":"v-approle-...","password":"...","lease_id":"...","ttl":300}

# 4. Job can read it (assuming user is in `vault` group, or file mode 644)
cat /run/vault/db-creds.json | jq .username
# "v-approle-..."
```

## 7. Run the job

The job needs no Vault configuration at all. Configure it to read the
file written by Vault Agent:

```bash
# Tell the job where the file is. Our demo's VaultConfig reads
# VAULT_SECRET_ID_FILE via the same mechanism.
export VAULT_ADDR=https://vault.example.com:8200
export VAULT_ROLE_ID=$(cat /etc/vault/role-id)
# secret_id is NOT exported here - the demo reads it from a separate
# channel. With Vault Agent, point the demo at the rendered file:
export VAULT_SECRET_ID_FILE=/run/vault/db-creds.json
#   ↑ NOTE: that's a JSON file containing username/password, NOT a raw
#     secret_id. For this demo you'd need to either:
#     (a) extract secret_id from the JSON before exporting
#     (b) add a small wrapper that re-exports DB creds as JDBC props

java -jar my-batch-job.jar
```

> **Reality check:** our demo currently expects `VAULT_SECRET_ID_FILE` to
> contain an AppRole `secret_id` (a UUID-ish string), not a DB
> credential JSON. With Vault Agent rendering DB credentials directly,
> you'd skip the AppRole layer entirely and just use the rendered file as
> JDBC properties. See "Variant: render JDBC properties directly" below.

---

## Variant: render JDBC properties directly (simpler for batch jobs)

Most batch jobs do not need an AppRole session at all — they just need
**valid DB credentials**. Vault Agent can render them straight into a
properties file the job consumes as JVM system properties:

```hcl
template {
  destination = "/run/vault/jdbc.properties"
  perms       = "0600"
  contents    = <<EOH
jdbc.url=jdbc:postgresql://db.internal:5432/mydb
jdbc.username={{ with secret "database/creds/mydb-role" }}{{ .Data.username }}{{ end }}
jdbc.password={{ with secret "database/creds/mydb-role" }}{{ .Data.password }}{{ end }}
EOH
}
```

Then run the job:

```bash
java -Dconfig.properties=/run/vault/jdbc.properties -jar my-batch-job.jar
```

**Important caveat:** this works well for short jobs (single credentials
pair for the whole run). For long jobs that need rotation, the file gets
overwritten by Vault Agent mid-run — your app's Hikari pool still has the
old password cached. This is exactly the situation Pattern B in
`docs/lifecycle.md` solves; combining them requires more glue (inotify
watcher on the properties file, signal the pool to reset on change).

---

## Operational notes

- **Secret-id renewal**: Vault Agent calls `auth/approle/role/<role>/secret-id`
  to fetch a fresh secret_id whenever the current one is within 10% of its
  TTL. You don't need to touch the VM after the bootstrap.
- **Lease renewal**: For dynamic DB credentials, Vault Agent renews
  leases as long as the templates are being rendered. If you stop the
  agent, leases will expire and the DB role will be revoked.
- **Log noise**: Vault Agent logs are chatty. Add a `log_level` block if
  needed:
  ```hcl
  log_level = "info"
  ```
- **First-boot failures**: if `/etc/vault/secret-id` is rejected (expired,
  wrong role), Agent retries every `retry.max_attempts` (default infinite)
  with `retry.backoff` seconds. Job won't see `/run/vault/db-creds.json`
  until auth succeeds.
- **Hardening**: the systemd unit above has `ProtectSystem=strict` +
  `ReadWritePaths=` — Agent can only write to the explicit dirs. Add
  `/run/vault` if missing on boot (systemd-tmpfiles or similar).

---

## Comparison to the wrapped-token pattern

| | Vault Agent (this doc) | Wrapping token (Demo E) |
|---|---|---|
| **Job knows about Vault** | No — only reads a file | Yes — makes HTTP call on startup |
| **Long-running daemon** | Yes (the agent) | No — job is one-shot |
| **Manual bootstrap needed** | Yes (initial secret_id) | No (CI/CD issues wrapping token) |
| **Auto-renew on lease expiry** | Yes | No (Pattern B in demo handles) |
| **Best for** | VMs with a sidecar slot | One-shot CI/CD-deployed jobs |

Pick Vault Agent when you control the VM lifecycle and can keep a
daemon running. Pick wrapping tokens for ephemeral CI/CD-deployed jobs
where no long-running process is acceptable.