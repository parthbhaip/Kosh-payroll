# NOTES - build loop memory

Append-only. Each iteration adds one section at the bottom: what was built, key files, decisions, gotchas.
When this file passes ~300 lines, compress the oldest entries into "Summary so far".

## Summary so far
Nothing built yet. The loop starts at T01.

## Log

## T01 Generate the Spring Boot project (2026-10-05)
- Recovered: an earlier iteration had generated `_starter/` from start.spring.io (default boot 4.1.1, confirmed latest via `/metadata/client`), copied it in with `cp -rn`, merged its `.gitignore` and added the extra deps, but never committed. Verified and finished it; nothing stashed.
- Versions: Spring Boot 4.1.1, Spring Security 7.1.1, Hibernate ORM 7.4.5.Final, Thymeleaf 3.1.5.RELEASE + `thymeleaf-extras-springsecurity6` 3.1.5.RELEASE (Boot-managed, works with Security 7), Flyway 12.4.0, H2 2.4.240, MySQL Connector/J 9.7.0, webjars-locator-lite 1.1.4, htmx WebJar `org.webjars.npm:htmx.org` 2.0.11, OpenPDF 3.0.5, Apache POI `poi-ooxml` 5.5.1. Maven wrapper 3.3.4 (only-script, Maven 3.9.16).
- Decision: htmx stays on 2.0.11 although 4.0.0 is on Central - CLAUDE.md rules (`htmx.config.includeIndicatorStyles`, `hx-swap-oob`, no `hx-on`) are written against the htmx 2 API, and htmx 4 renamed config and changed attribute inheritance. Revisit only with a deliberate task.
- Boot 4 gotchas: starters are modular - `spring-boot-starter-webmvc` (not `-web`), `spring-boot-h2console` for the H2 console, and one `*-test` starter per starter. `@AutoConfigureMockMvc` lives in `org.springframework.boot.webmvc.test.autoconfigure`. OpenPDF 3.x uses package `org.openpdf.text` (not `com.lowagie`).
- Tests: `KoshApplicationTests` (context loads with the Security dialect; htmx served version-less at `/webjars/htmx.org/dist/htmx.min.js`), `DocumentLibrariesTest` (OpenPDF table + text extraction, POI XLSX round-trip). No profiles yet - T02 adds them; until T07 every URL needs auth (tests use `@WithMockUser`).
- Review fixes: `mvnw` is staged as 100755 (`git add --chmod=+x mvnw`; `core.filemode=false` here, so a plain `git add` keeps the index mode but never sets it). Check `git ls-files -s mvnw` before any commit that re-adds it.
- Decision: `spring.h2.console.enabled=false` in `application.properties`, because `spring-boot-h2console` ships a devtools default of `true` that would open `/h2-console` under every profile in a non-packaged run. T02 sets it to `true` only in `application-dev.properties`. MockMvc only goes through the DispatcherServlet, so a `/h2-console` 404 check proves nothing. `KoshApplicationTests.h2ConsoleIsOffOutsideDev` asserts the property instead, and checks that no `h2Console` bean exists.
- Tests use constructor injection too: an `@Autowired` constructor with final fields, never `@Autowired` fields.
