# 09: Keep Controller-to-Driver Calls Reliable During Long Workloads

**What to build:** A Controller client that calls the new Driver over authenticated HTTP with stable connections and bounded recovery behavior, so an active pressure test continues through token and transient network conditions.

**Blocked by:** 04: Run Controller as a Spring Boot Application; 08: Preserve the Driver `/i` HTTP Contract.

**Status:** ready-for-agent

- [ ] Controller calls each Driver `/i` endpoint using the established wire contract and independently configured service credentials.
- [ ] Internal HTTPS/TLS and protected token configuration work without placing secrets in logs, workload data, or packaged images.
- [ ] A workload remains connected across the configured authentication lifetime; use a non-expiring long-lived service token or transparent refresh that does not require workload restart or operator action.
- [ ] Connection pooling, keep-alive, and separate connect/read/command/workload timeouts are configurable and validated at startup.
- [ ] Only transient connection failures and explicitly retryable 5xx responses receive a bounded retry; write requests carry idempotency identity and do not duplicate work.
- [ ] An integration test crosses the configured token/session expiry boundary while a workload remains active and verifies no authentication disconnect.
