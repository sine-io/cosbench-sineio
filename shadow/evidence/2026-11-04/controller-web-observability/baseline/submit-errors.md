# Legacy Submit Error Contract

These normalized values were captured from the frozen Controller replay on 2026-11-04 using an authenticated `POST /controller/submit-workload.do` with the `config` multipart field. Both the 9-byte malformed XML sample (`<workload`) and an 11 MiB all-zero file returned the legacy submit page.

| Response field | Legacy value |
| --- | --- |
| HTTP status | `200` |
| `Content-Type` | `text/html;charset=UTF-8` |
| Page title | `Workload Submission` |
| Error element | `<span class="error"><strong>Note</strong>:` |
| Parser message | `cannot parse the xml configuration from <stream>, due to XML document structures must start and end within the same entity.` |

The error-response body is rendered by the legacy submit view, not the HTTP 413 upload-limit response. The migrated Controller uses `web.maxUploadBytes: 0` by default so these requests reach the same parser error view. A positive configured limit remains available and is independently verified to return 413 for an oversized request. Parser normalization is limited to the observed Java 21 prolog diagnostic; other SAX parser messages remain specific to their parse error.
