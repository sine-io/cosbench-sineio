# 11: Preserve Driver Web Behavior on Boot 3

**What to build:** The existing Driver pages served by the Driver Boot process, preserving mission/worker operations and their FreeMarker output while migrating Java Web adapters to Spring 6/Jakarta.

**Blocked by:** 05: Run Driver as a Spring Boot Application; 08: Preserve the Driver `/i` HTTP Contract; 09: Keep Controller-to-Driver Calls Reliable During Long Workloads.

**Status:** ready-for-agent

- [ ] Existing Driver page routes, view names, forms, template variables, FreeMarker templates, and static resources render compatibly with the baseline.
- [ ] Driver login, mission status, worker display, and page-triggered operations retain their existing external behavior.
- [ ] The Driver `/i` command backend and Driver UI coexist in the same Boot process without route or session conflicts.
- [ ] Page, form, and response tests compare observable output to baseline fixtures rather than internal handler structure.
- [ ] The Web backend uses Spring 6/Jakarta and Boot-managed embedded Tomcat without OSGi Web contexts or a separately packaged old Tomcat.
