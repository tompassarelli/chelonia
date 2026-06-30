# Coordinator: wire protocol + configurable bind

This is the **seam** between Fram (the engine) and a gateway-fronted consumer like
Tern. It documents the one interface the two repos meet at — the coordinator's
wire protocol — and the configurable bind that lets the coordinator run behind a
gateway on a private network.

> Originally a hand-off from the Tern multi-tenant work; the engine-side change
> (configurable bind) is **implemented** (`cnf_coord_daemon.clj` `bind-cfg`/`serve`)
> and tested (`bind_test.clj`). Kept here as the contract of record.

## The seam (who owns what)

The two repos meet at **exactly one interface: the coordinator's wire protocol.**

| Concern | Owner |
|---|---|
| Claim kernel, fold, Datalog, structural integrity (`validate`) | **Fram** |
| Coordinator daemon: socket, **bind address**, wire protocol, sole-writer lock | **Fram** |
| Authentication, tenant routing, rate limit, audit, body caps | **Consumer gateway** (e.g. Tern) |
| Tenant provisioning, lifecycle projections, time/billing | **Consumer** |

**The contract** (must stay stable, or be versioned): a client opens a TCP
connection, writes **one line of EDN** (the request), reads **one line of EDN**
(the response). The coordinator carries **no auth and no domain code** — auth is the
gateway's job, lifecycle is the consumer's. If Fram changes this protocol it is a
**breaking change for any gateway**; version it or keep it additive.

Request/response surface (`cnf_coord_daemon.clj` `handle`):

```
{:op :version}                          -> {:version <n>}
{:op :status}                           -> {:version <n> :claims <n> :log "<path>"}
{:op :validate}                         -> {:violations [...]}
{:op :assert  :te "@id" :p "pred" :r v :base <n>} -> {:ok <n>} | {:conflict ...}
{:op :retract :te "@id" :p "pred" :r v :base <n>} -> {:ok <n>} | {:conflict ...}
{:op :subscribe}                        -> {:subscribed <n>} then a stream of events
(unknown)                               -> {:error "unknown op"}
```

## Configurable bind (`FRAM_BIND`)

The daemon binds **loopback by default**, so no single-machine user is silently
exposed. `FRAM_BIND` overrides it for gateway-fronted / cross-host deployment:

- unset / `loopback` / `127.0.0.1` → binds `127.0.0.1` (default; unchanged).
- any other value (e.g. `0.0.0.0`) → binds that address and logs a **one-time
  WARNING to stderr** that the protocol is UNAUTHENTICATED and must sit behind the
  gateway / a firewall.

**Recommended cross-host value: `FRAM_BIND=0.0.0.0`.** It binds *all* interfaces
*including loopback*, so the local CLI and `fram-up` doctor (which connect to
`127.0.0.1`) keep working, and isolation is enforced by the **network** (firewall /
private network / the gateway as sole ingress) rather than by binding a single IP.
(Binding a single non-loopback IP would break the local clients; if you need that,
make the client connect address configurable too — not currently implemented.)

### Security invariant (must hold)

The protocol is unauthenticated by design. Binding non-loopback is only safe paired
with a network boundary where **the only thing that can reach the port is the
gateway** (which authenticates). **Never publish the raw port.** Default-loopback +
the loud warning is what protects existing users.

## Securing a non-loopback bind (mTLS)

Over an **untrusted** link, use mutual TLS. **Engine-terminated mTLS ships** — set
all three on the daemon:

```
FRAM_TLS_KEYSTORE=server.p12    # PKCS12: the coordinator's cert+key
FRAM_TLS_TRUSTSTORE=trust.p12   # PKCS12: the client CA/cert it will accept
FRAM_TLS_PASS=...               # store password
```

The listener becomes an `SSLServerSocket` with `setNeedClientAuth(true)` — it
**requires and verifies a client cert** (mutual TLS). Unset → plaintext (default,
unchanged). The EDN wire protocol is identical inside the TLS session. Clients
present a cert with the same `FRAM_TLS_*` vars plus `FRAM_CONNECT=<coordinator-host>`
(`fram.rt` connects with an `SSLSocket`); this works on **babashka** — only the
*server*-side socket needs the JVM, which is why **the daemon runs on the JVM** (bb's
native image has no `SSLServerSocket`; the CLI/MCP stay on bb). Verified end-to-end
by `tls_test.clj` (trusted cert in; plaintext + wrong-cert rejected).

**Alternative — stunnel sidecar** (`deploy/stunnel.example.conf`): keep the
coordinator loopback+plaintext and front it with stunnel for TLS. Zero engine TLS
config; useful if you'd rather manage certs outside the engine. Either way, **never
publish the raw plaintext port.**

## Target topology this enables

```
internet ─TLS▶ gateway ─┬─ coordinator-acme   (FRAM_BIND=0.0.0.0, port 7801, not published)
                        └─ coordinator-globex (FRAM_BIND=0.0.0.0, port 7802, not published)
```

Each tenant's coordinator runs as a separate container/host on a private network;
the gateway (the only public ingress) authenticates and routes to the right
coordinator by its private host/port.

## Verification

- `bb bind_test.clj` — asserts both bind modes (default loopback via `ss`;
  `FRAM_BIND=0.0.0.0` binds all interfaces, loopback still answers, warning logged).
- Tern's gateway smoke test is the cross-repo contract regression check:
  `FRAM_HOME=/path/to/fram bash deploy/gateway/smoke_test.sh` (run from a Tern
  checkout) — exercises auth, body cap, audit, revocation over the wire protocol.
