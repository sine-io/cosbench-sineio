# 10: Preserve Controller Web and CLI Behavior on Boot 3

**What to build:** The existing Controller Web experience served by the Controller Boot process, preserving pages and operational actions while migrating only the Java backend and Web runtime to Spring 6/Jakarta.

**Blocked by:** 04: Run Controller as a Spring Boot Application; 09: Keep Controller-to-Driver Calls Reliable During Long Workloads.

**Status:** ready-for-agent

- [ ] Existing Controller routes, view names, forms, template variables, FreeMarker templates, and static resources render compatibly with the baseline.
- [ ] Login/session behavior, workload submission, multipart CLI handling, downloads, CSV export, and Prometheus endpoint remain functional.
- [ ] Controller CLI actions can submit, query, cancel, reorder, and resubmit workloads through the new Controller service and Driver HTTP client.
- [ ] External response behavior and downloaded content are compared with baseline fixtures; tests do not depend on handler class names.
- [ ] The Web backend uses Spring 6/Jakarta and Boot-managed embedded Tomcat without OSGi Web contexts or a separately packaged old Tomcat.
