# 08: Preserve the Driver `/i` HTTP Contract

**What to build:** A Driver HTTP backend that accepts the existing Controller commands and produces compatible results while using the new Boot/Jakarta runtime and Provider services.

**Blocked by:** 05: Run Driver as a Spring Boot Application; 06: Discover Mock Providers Through the Static Registry; 07: Load SineIO and S3 Providers in the Production Runtime.

**Status:** ready-for-agent

- [ ] `ping`, `submit`, `login`, `launch`, `query`, `trigger`, `close`, and `abort` retain their existing paths, request fields, response fields, status codes, and error semantics.
- [ ] Valid and malformed requests produce the same externally visible mission state transitions and diagnostics as the baseline.
- [ ] Contract tests exercise Mock-backed submit/login/launch/query/trigger/close/abort behavior through HTTP.
- [ ] Authentication failures, unreachable providers, timeouts, and retry boundaries produce the established error classifications without exposing secrets.
- [ ] Repeated write requests with the same request identity do not create duplicate mission actions.
