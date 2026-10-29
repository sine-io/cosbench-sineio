# Driver HTTP Contract Spike

Status: executable Driver HTTP contract spike with real COSBDriverService + Mock auth/storage verification.

## Identity and authentication

Controller calls use HTTPS when `auth.tls.enabled` is true and always carry `Authorization: Bearer <service-token>`. The token is resolved by the existing versioned YAML secret resolver from one environment variable or protected file. Driver service auth is checked before command parsing or business service invocation; page cookies and `/i/login.command` Provider credentials do not authorize service requests.

Write commands are `submit`, `login`, `launch`, `close`, `abort`, and `trigger`. Each carries both `X-COSBench-Request-Id` and `Idempotency-Key`; they share the same opaque UUID. Controller retains both values and the exact body for every retry. Driver scopes keys to the authenticated service principal and `/i` command name. The fingerprint is SHA-256 over the UTF-8 command name, a zero byte separator, and the exact request body bytes. Existing command bodies are sent unchanged. The executable vertical spike exercises submit and query through the real DriverService/Mock mission lifecycle; remaining command adapters, especially the script-running trigger handler, are covered by ticket #13 and preserve their legacy business implementations.

The request ID identifies an attempt lineage for diagnostics. The idempotency key is the deduplication identity. Reusing a key within the same principal and command scope with the same body returns the original status and body, including a stored 4xx/5xx response. Reusing it in that scope with a different body returns 409 and does not call the driver port. The same opaque key under another command is a separate identity scope. Missing either identity header returns 400. Query and ping require service auth but do not require write identity or enter the idempotency store. The real HTTPS integration boots `COSBDriverService` with Mock auth/storage and exercises submit, replay, query, login, launch, close, and query after completion. It also drops the first submit response through Toxiproxy after Driver processing and verifies that `AuthenticatedDriverClient` retries with the same key and bytes while only one mission is created. A late replay after mission completion returns the original submit response.

## Status and retry rules

| Condition | Status | Retried | Fixture |
| --- | ---: | --- | --- |
| Missing, malformed, or invalid service token; cookie only | 401 | No | `driver-service-auth-missing.json` / `driver-service-auth-invalid.json` |
| Write identity missing | 400 | No | `driver-service-identity-missing.json` |
| Same principal+command key with changed body | 409 | No | `driver-idempotency-conflict.json` |
| Existing `/i` business response | Existing response status/body | No, except explicitly retryable server/transport failures | baseline `/i` fixtures |
| Connection failure / connection or read timeout | no HTTP response; diagnostic includes request ID and unknown-result warning | Up to `http.retries.maxAttempts` total attempts | `shadow/evidence/2026-10-29/http/` |
| HTTP 502/503/504 | Original HTTP response after limit | Yes, bounded | `shadow/evidence/2026-10-29/http/` |
| Other 4xx, including 401/409 | Original response | No | expected fixtures |
| Other 5xx | Original response | No | expected fixtures |

For write transport failures, retries reuse the same URI, headers, and bytes. A successful authenticated response advertises `X-COSBench-Driver-Instance`, an opaque per-process identifier. Before its first write, the client obtains this identifier through authenticated ping and sends it in each write request. Driver rejects a mismatched identifier with 409 before entering the idempotency store. This prevents a retry aimed at a restarted Driver from executing an ambiguous write again; the client reports that result as unknown and clears its cached instance for a later, distinct call. Driver keeps completed and in-flight results for its process lifetime, but does not persist them across restart. MVP service credentials are configured long-lived tokens with no natural expiry, so active workloads do not depend on refresh. Operators rotate them through the configured secret source and restart the services between workloads; TLS protects tokens in transit.

## Resource and timeout policy

`http.pool.maxConnections` is enforced by a semaphore shared by endpoint clients created through `DriverClientRegistry.pooled`; `maxPerRoute` is enforced by each endpoint client. The test suite verifies the shared semaphore identity across endpoints, request concurrency bounds, and that idle eviction keeps an active call alive. Clients use HTTP/1.1 keep-alive and are reused per configured Driver endpoint. `idleTimeout` is the maximum period before an unused endpoint client is evicted by the owning registry. `connect`, `read`, `command`, and `workload` remain distinct settings; an individual command timeout is capped by read timeout, while workload orchestration owns the longer workload deadline. Retry backoff grows linearly as `backoff * attempt`, and `maxAttempts` is total attempts, including the first call.

The in-memory idempotency store retains each unique write result for the process lifetime to preserve late retries after mission completion; it has no TTL or eviction in this spike. Its memory use therefore grows with unique writes and response sizes. Production bounds must be selected from peak write rate times maximum Driver uptime and measured response sizes, or a separately specified bounded store must define what happens to keys after eviction. A persistent store would alter crash recovery and is deliberately outside this spike. The HTTP adapter composes the legacy DriverService contract and retains its mission command service calls; script-based trigger side effects remain delegated to the existing Driver web handler implementation and are not executed by contract fixtures.

## Replay

Run the Docker harness with the HTTP tests in the Java 21 container:

```sh
shadow/harness/run.sh /tmp/cosbench-scheme3-http -- \
  /bin/bash /workspace/shadow/harness/http-contract.sh
```

The harness creates a fresh self-signed test certificate, injects its keystore/truststore and service token into the ephemeral test container, starts MinIO/Toxiproxy, runs the shadow reactor checks and targeted HTTP tests, and writes a dependency tree plus protocol evidence. `sudo docker compose down --volumes --remove-orphans` is performed by the harness on both success and failure. The evidence directory records the passing Java 21 run, test reports, dependency tree, and zero remaining containers, networks, or volumes.

Toxiproxy's disconnect, response truncation, and timeout controls are verified in the Docker harness. The real HTTPS service response-loss integration routes the embedded Spring Driver endpoint through Toxiproxy, removes the truncation toxic during the client's bounded retry backoff, and checks that one Mock mission is retained. Client reuse is verified through registry identity and idle-eviction behavior, but accepted TCP connection counts are not measured at the socket layer in this spike.
