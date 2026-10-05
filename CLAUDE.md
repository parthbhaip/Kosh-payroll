# Kosh - Employee Payroll System

Payroll and HR system for one Indian company: employees, salary structures, attendance and leave, monthly payroll with maker-checker approval, Indian statutory deductions (PF, ESI, Maharashtra PT, TDS), payslips, compliance files, employee self-service, and a REST API for the employee mobile app. Built entirely in Java.
This file is the rulebook and is loaded in every session. The build loop is driven by PROMPT.md (one iteration) and PLAN.md (the task list).

## Project settings (edit BEFORE the first run - renaming after T01 is painful)
- Brand: **Kosh** - tagline: "Payroll that closes on time."
- Base package `com.kosh` - Maven groupId `com.kosh`, artifactId `kosh`
- Demo company (seed data only, fictional): **Panchganga Precision Tools Pvt. Ltd.**, Kolhapur, Maharashtra. Factory at MIDC Shiroli, head office in Rajarampuri, sales office in Pune.
- Currency INR with Indian digit grouping: `₹1,23,456.50` (`en-IN`). Time zone Asia/Kolkata.
- Tax year runs April-March. The current tax year is 2026-27 (Income-tax Act, 2025).
- Demo accounts (dev seed only), password for all `Demo@1234`:
  `admin@kosh.test`, `hr@kosh.test`, `payroll@kosh.test`, `finance@kosh.test`, `manager@kosh.test`, `employee@kosh.test`

## Sources of truth for money (read before touching any calculation)
- `docs/STATUTORY-RATES.md` - every statutory rate, ceiling and slab, with sources. These are seeded into effective-dated database tables. Never hard-code a rate in Java.
- `docs/PAYROLL-RULES.md` - every formula, rounding rule and worked example. Each worked example MUST exist as a unit test with exactly those numbers.
- If a rule you need is not in these files, do not invent it. Choose the conservative, documented option, add it to PAYROLL-RULES.md under "Project decisions", and note it in NOTES.md.

## Roles (one user can hold several roles; every person who works there is also an EMPLOYEE)
| Role | Can do |
|---|---|
| ADMIN | company settings, statutory rule versions, users and roles, audit log, reopen a finalized payroll |
| HR | employees, org structure, salary structures and assignments, leave and attendance policies, exits, declaration verification |
| PAYROLL | payroll inputs, run and calculate payroll, submit for approval, payslips, compliance files |
| FINANCE | approve or reject payroll (never one they submitted), bank files, mark payroll paid, finance reports, reimbursement final approval |
| MANAGER | own team only: leave, attendance regularisation and reimbursement approvals; never sees salary data |
| EMPLOYEE | own profile, payslips, tax regime and declarations, leave, attendance, reimbursements, change requests |

Salary figures are visible only to ADMIN, HR, PAYROLL and FINANCE (and the employee for their own record).

## Stack (fixed - do not swap anything)
- Java 21 language level. Maven through the wrapper (`mvnw`). No Gradle.
- Spring Boot 4.x, latest stable, generated from start.spring.io in T01. Record exact versions in NOTES.md.
- Spring MVC + Thymeleaf (server-rendered HTML) + Thymeleaf Spring Security extras. htmx from a WebJars Maven dependency. No hand-written fetch/AJAX code.
- Spring Security (form login for the website; JWT resource server for `/api/**` in Phase 10).
- Spring Data JPA (Hibernate) + Flyway. H2 file DB in `dev`, H2 in-memory in `test`, MySQL 8 in `mysql` profile.
- Bean Validation, Spring Mail (logging sender in dev/test, SMTP in mysql).
- PDF: OpenPDF (`com.github.librepdf:openpdf`, latest stable that runs on Java 21) with its table API; BouncyCastle only if PDF password protection needs it. Excel: Apache POI (`poi-ooxml`). If a coordinate or version has moved, check Maven Central and record the choice in NOTES.md.
- Tests: JUnit 5, Spring Boot Test, MockMvc, spring-security-test, AssertJ. springdoc-openapi in Phase 10.

Not allowed: Node/npm or any frontend build step, React/Vue/Angular, Tailwind, Bootstrap or any CSS framework, jQuery, CDN-hosted JavaScript, chart libraries, Lombok, Kotlin, Gradle.
Small vanilla JS files in `static/js/` only where HTML + htmx cannot do the job: under ~60 lines each, no inline scripts, no inline `on*=` handlers.

## Architecture
Package by feature under `com.kosh`:
`common` (BaseEntity, errors, formatting, amount-in-words, masking), `config`, `security`, `audit`, `user`, `company` (profile, registrations, pay settings, holidays), `org` (departments, designations, locations, grades, cost centres), `statutory` (effective-dated rule tables and lookups), `employee`, `salary` (components, structures, assignments, revisions), `leave`, `attendance`, `payroll` (runs, inputs, workflow, calculator, arrears), `loan`, `payslip`, `compliance` (PF ECR, ESI, PT, TDS outputs, bank files), `tax` (regime, declarations, TDS projection), `reimbursement`, `exit` (separation, full and final), `report`, `notification`, `selfservice`, `manager`, `api`, `storage`, `web` (public pages).

Layering: Controller -> Service -> Repository.
- Controllers are thin and bind to form/DTO classes (records or plain classes with Bean Validation), never to entities.
- Services own business rules, `@Transactional` boundaries, role and ownership checks, and state changes.
- **Calculation code is pure Java**: `PayrollCalculator`, `PfCalculator`, `EsiCalculator`, `PtCalculator`, `TdsCalculator`, `GratuityCalculator` take immutable inputs (records) plus the applicable rule versions and return immutable results. No repositories, no Spring, no clock inside them. That keeps them fully unit-testable.
- Payroll run status changes go ONLY through `PayrollWorkflow` (allowed transitions, who may make them, maker-checker, timestamps, audit).
- Money: `BigDecimal`, never `double`/`float`. Rounding exactly as PAYROLL-RULES.md says.
- Time: `LocalDate`/`LocalDateTime` in Asia/Kolkata from an injected `java.time.Clock` bean.
- Finalized payroll results are immutable snapshots (payslip header + lines with names, amounts and rule versions used). Later changes to salary, rules or employee data never alter a finalized payslip.
- Repositories use fetch joins or entity graphs on list pages. No N+1 queries, especially in the payroll run.
- Constructor injection only. No field injection, no static mutable state.
- Web and API share the same services. No business logic in controllers.

## Database rules
- Flyway is the only schema source. `spring.jpa.hibernate.ddl-auto=validate`, `spring.jpa.open-in-view=false`.
- Migrations: `src/main/resources/db/migration/V{n}__{snake_case}.sql`. NEVER edit a committed migration. Add a new one.
- SQL must run on H2 (`MODE=MySQL;DATABASE_TO_LOWER=TRUE`) and MySQL 8: `BIGINT AUTO_INCREMENT` keys, explicit `VARCHAR` lengths, `DECIMAL(12,2)` money (`DECIMAL(7,4)` rates), `BOOLEAN`, `DATE`, `DATETIME(6)`, named foreign keys and indexes, no vendor-only functions.
- Index every foreign key and every column used in WHERE/ORDER BY on list pages.
- Statutory rules are effective-dated rows (`effective_from`, nullable `effective_to`). Adding a new version closes the previous one. Past versions are never edited.

## Sensitive data
- PAN and bank account numbers are encrypted at rest with AES-GCM through a JPA `AttributeConverter`. The key comes from env var `APP_DATA_KEY` (base64, 32 bytes). Dev uses a fixed dev-only key in `application-dev.properties`; tests generate one. A keyed hash column (HMAC-SHA256) supports uniqueness checks on PAN.
- The UI shows bank account numbers masked (`XXXXXX4321`) everywhere except the bank file export. PAN is masked in lists and shown in full only on the employee's own profile, on the payslip and to HR.
- Never store Aadhaar numbers. Never log PAN, bank details, salaries, passwords or tokens.
- Validation: PAN `^[A-Z]{5}[0-9]{4}[A-Z]$`, IFSC `^[A-Z]{4}0[A-Z0-9]{6}$`, UAN 12 digits, ESI IP number 10 digits, Indian mobile 10 digits starting 6-9, pincode 6 digits.

## UI and design system (must look like real B2B software)
Quality bar: calm, precise, trustworthy financial software that a recruiter would believe is in production. Not a template look, not a student-project look.
- No lorem ipsum, no "coming soon", no dead links or buttons, no unstyled browser controls.
- Copy is short, plain and specific: "September payroll is ready for approval", "3 employees have no bank details", "Paid on 30 Sep 2026".
- Everything is original. Never copy another product's logo, name, colours or layout signatures.

Tokens in `static/css/tokens.css` as CSS variables; everything else uses the variables. All pairs below were contrast-checked with the WCAG formula.
- Primary `#2647B5` (7.9:1 on white), hover `#1E3A94`, tint `#EEF2FD`
- Accent teal `#0F7B6C` (positive figures, highlights)
- Ink `#161A23`, secondary text `#475063`, muted text `#5E6677` (5.4:1 on page background)
- Page background `#F6F7F9`, surfaces `#FFFFFF`, dividers `#E4E7EC` (decorative only), control borders `#808999` (3.3:1, needed for inputs)
- Success `#157A4A` on tint `#E9F6EF`, danger `#B42318` on `#FDECEA`, warning `#9A5B06` on `#FDF3E1`
- Radius 6 / 10 / 16 px, pill 999px. Soft 1-2 layer shadows. Spacing on a 4px scale.
- Fonts: IBM Plex Sans (UI, 400/500/600/700) and IBM Plex Mono (employee codes, UANs, UTRs), self-hosted woff2 in `static/fonts/` so the demo works offline. All numbers use `font-variant-numeric: tabular-nums`.

Layout and components:
- App shell: left sidebar (248px) with grouped navigation by role (Overview, People, Time, Payroll, Compliance, Reports, Settings); it collapses to icons between 960 and 1200px and becomes a drawer below 960px. Top bar with page title, breadcrumbs, employee search, notifications bell and user menu (shows active roles).
- Public layout for the landing page, login and legal pages.
- Components in `static/css/app.css`: buttons (primary, secondary, ghost, danger; sm/md; disabled + loading), inputs/select/textarea/date with label, help and error, checkbox, radio, switch, cards, stat cards, data tables (sticky header, sortable headers, right-aligned money, row hover, totals row, responsive: horizontal scroll inside the card on phones), filter bar, badges (payroll states, employee status), tabs, multi-step stepper, timeline, description lists, toasts/flash, modal (native `<dialog>`), drawer, empty states with simple original line illustrations, skeleton loaders, pagination.
- Charts are server-side SVG built in Java (no chart library), with labelled axes and accessible titles.
- Money display: `₹` + en-IN grouping, two decimals in tables, tabular figures, negatives in danger colour with a minus sign.
- Breakpoints 640 / 960 / 1200px. Self-service pages (payslips, leave, attendance, reimbursements) are mobile-first; admin pages are desktop-first but must still work at 360px.
- Accessibility: WCAG AA, visible focus rings, a label on every input, `aria-live` for toasts, keyboard-usable dialogs and drawers, skip link, `lang="en"`, tables with `<th scope>` and captions.
- Icons: one sprite `static/img/icons.svg` of original line icons (24px grid, 1.75 stroke, `currentColor`).
- Templates: `templates/layouts` and `templates/fragments`; every page uses a layout. Page titles: `Page name | Kosh`.
- Forms: server-side validation with inline messages, keep input on error, Post/Redirect/Get with a flash message.
- htmx is progressive enhancement only: every action still works as a normal form POST with JavaScript off. For `HX-Request` return a Thymeleaf fragment; keep counts in sync with `hx-swap-oob`.

## Security rules
- BCrypt passwords. CSRF on for all web forms; htmx sends the token from meta tags via an external JS config file.
- Content-Security-Policy: `default-src 'self'; script-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'` (dev may relax it for `/h2-console/**` only). So: no inline `<script>`, `htmx.config.includeIndicatorStyles = false`, no `hx-on`.
- Authorization in two layers: URL rules in the security config AND checks in services (employee -> own records only; manager -> own team only, never salaries; salary data -> ADMIN/HR/PAYROLL/FINANCE). Every such endpoint gets a test proving another user gets 403 or 404.
- Maker-checker: the user who submitted a payroll run cannot approve it; a salary assignment or revision needs approval by a different HR or ADMIN user; bank detail changes need HR approval.
- Audit log for logins (success and failure), role changes, salary and bank changes, statutory rule versions, every payroll state change, payslip regeneration, exports of compliance or bank files.
- Never `th:utext` with user-supplied content. Uploads: allowlist pdf/jpg/png checked by magic bytes, max 5 MB, random names, stored under `./uploads` outside the classpath, served only through an authorised controller (never as public static files).
- Secrets only from environment variables. `.env` is git-ignored; `.env.example` is committed. H2 console only in `dev`.
- Compliance files are labelled "Prepared for filing - verify before upload". Annual tax statements are labelled "Form 130-style statement generated by Kosh - not the official TRACES certificate".

## Commands
- Full build + all tests: `./mvnw -q verify` (Bash) or `.\mvnw.cmd -q verify` (PowerShell)
- One test class: `./mvnw -q -Dtest=ClassName test`
- Do NOT start the server (`spring-boot:run`) inside the loop. It never exits. Prove pages work with MockMvc tests.
- If the wrapper script is not executable in Bash: `chmod +x mvnw`.
- This machine's default `java` on PATH is a broken Java 8 shim. Always point Maven at JDK 21 in the same command: Bash `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot" ./mvnw -q verify`; PowerShell `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"; .\mvnw.cmd -q verify`.

## Testing rules
- Every task ships with tests for its acceptance criteria.
- Every worked example in PAYROLL-RULES.md is a unit test with exactly those inputs and outputs. If the code disagrees, the code is wrong unless you prove the document wrong; then fix the document, explain why in NOTES.md, and update the test.
- `@DataJpaTest` for custom queries. `@SpringBootTest` + MockMvc for pages and APIs: status, view/fragment, key content, and security (anonymous, wrong role, wrong employee, manager of another team).
- Tests run on the `test` profile (H2 in-memory) with a fixed `Clock`.
- The build must be green before every commit. Never delete or weaken a test just to go green.

## Git
- One task = one commit: `Txx: <task title>`. Never push, never force, never rewrite history.
- Never run `git reset --hard`, `git clean`, `git checkout -- .` or delete stashes. To set work aside: `git stash push -u -m "<reason>"`.

## NOTES.md (shared memory between iterations)
- Append-only log: what each task built, key files, decisions, gotchas. Read the end of it before starting work.
- When it passes ~300 lines, compress the oldest entries into the "Summary so far" section at the top.
- Nobody is watching the loop. Never ask questions; make the reasonable choice and record it in NOTES.md.
