# PLAN - Kosh build tasks

How this file works
- The loop always takes the FIRST `- [ ]` task from the top. The order is the dependency order. Do not skip ahead.
- Status: `- [ ]` open, `- [x]` done, `- [!]` blocked (reason after `-- BLOCKED:`).
- The indented bullets under a task are its acceptance criteria. All of them must be true before the task is checked.
- New tasks: next free ID, placed in the right phase. If a task is too big for one iteration, finish a coherent part, commit it, and add the rest as `Txxb` directly below. Never delete tasks.
- Money rules live in `docs/PAYROLL-RULES.md`, rates in `docs/STATUTORY-RATES.md`.

---

## Phase 0 - Foundation

- [x] T01 Generate the Spring Boot project
  - Use the start.spring.io HTTP API (curl) to generate a Maven project into `_starter/`: latest stable Spring Boot 4.x, Java 21, groupId `com.kosh`, artifactId `kosh`, name `Kosh`, package `com.kosh`, jar packaging. Dependencies: web, thymeleaf, security, data-jpa, validation, flyway, h2, mysql, mail, devtools, actuator. If an ID is rejected, check `https://start.spring.io/dependencies` for current IDs.
  - Copy into the repo root WITHOUT overwriting existing files (`cp -rn _starter/. .`), then merge the starter's `.gitignore` entries into ours. `_starter/` stays git-ignored.
  - Add: htmx WebJar, `webjars-locator-lite`, Thymeleaf Spring Security extras (matching the generated Spring Security), OpenPDF, Apache POI `poi-ooxml`.
  - `./mvnw -q verify` passes. Record Spring Boot, Spring Security, Hibernate, Thymeleaf, htmx, OpenPDF and POI versions in NOTES.md.

- [x] T02 Configuration and profiles
  - `application.properties`: app name, `spring.profiles.default=dev`, `ddl-auto=validate`, `open-in-view=false`, response compression, whitelabel error page off, multipart max 5 MB.
  - `application-dev.properties` (H2 file DB `./data/kosh`, MODE=MySQL, H2 console, logging mail sender, dev-only `APP_DATA_KEY`, `app.seed-demo=true`), `application-test.properties` (H2 in-memory), `application-mysql.properties` (DB_URL / DB_USER / DB_PASSWORD, SMTP, APP_DATA_KEY from env; `app.seed-demo` from env var `SEED_DEMO`, default false).
  - Typed, validated `@ConfigurationProperties` `AppProperties`: brand, zone, upload dir, seed flag, data key, base URL for links in emails.
  - `.env.example` lists every env var with a comment. Context-loads test passes on the `test` profile.

- [x] T03 Core building blocks
  - `BaseEntity` (id, createdAt, updatedAt via JPA auditing, `@Version`), `Clock` bean (Asia/Kolkata), `NotFoundException`, `ForbiddenException`, `@ControllerAdvice` mapping them to error pages.
  - Thymeleaf helper bean `@fmt`: money (`₹1,23,456.50`), compact money (`₹1.2 L`, `₹3.4 Cr`), dates (`30 Sep 2026`), month (`September 2026`), relative time, masked account (`XXXXXX4321`), masked PAN (`ABXXXXX4F`).
  - `AmountInWords` (Indian system: thousand, lakh, crore, paise) passing every W12 example.
  - `EncryptedStringConverter` (AES-GCM, random IV per value, key from AppProperties) and `HmacHasher` for lookup hashes.
  - Branded 403, 404 and 500 pages. Unit tests for formatters, words, encryption round-trip, tamper detection.

- [x] T04 Design system
  - `static/css/tokens.css` + `static/css/app.css` implementing every token and component in CLAUDE.md > UI, including hover/focus/disabled states and the responsive data table.
  - Self-hosted IBM Plex Sans (400, 500, 600, 700) and IBM Plex Mono (400, 500), latin woff2 from Fontsource in `static/fonts/`, `font-display: swap`.
  - `static/img/icons.svg` original line icons: dashboard, users, user, building, calendar, clock, wallet, receipt, file, file-text, download, upload, check, x, plus, minus, edit, trash, search, filter, bell, settings, shield, lock, bank, chart, briefcase, logout, chevron-down, chevron-right, menu, alert, info, rupee, send.
  - Original SVG logo (mark + wordmark) `static/img/logo.svg` and `favicon.svg`. 4 original empty-state line illustrations.
  - Dev-only page `/dev/ui-kit` showing every component and state. MockMvc test: renders in dev, 404 elsewhere.

- [x] T05 Layouts, app shell and public pages
  - `layouts/public.html` and `layouts/app.html`. App shell per CLAUDE.md: sidebar groups shown by role, active item, collapse states, mobile drawer, top bar with breadcrumbs, search box, bell placeholder count, user menu with roles and sign-out.
  - Fragments: flash/toast, form field (label/help/error), pagination, empty state, money, status badge, stat card, data table header with sort links, stepper, timeline, description list.
  - htmx from WebJars on every page; CSRF wired via `static/js/htmx-config.js`; indicator styles disabled.
  - Landing page `/` (hero "Payroll that closes on time", feature grid, how a payroll run works in 4 steps, compliance strip PF/ESI/PT/TDS, sign-in CTA), Privacy, Terms, Help/FAQ with sensible real copy.
  - Tests: both layouts render, public pages 200, security headers present (CSP, X-Content-Type-Options, frame-ancestors).

## Phase 1 - Identity, access and audit

- [x] T06 Users and roles
  - `User` entity + migration: email (unique, lower-case), password hash, display name, enabled, roles (ADMIN, HR, PAYROLL, FINANCE, MANAGER, EMPLOYEE; many per user), failed login count, locked-until, last login, optional link to Employee (added in T15).
  - Repository with case-insensitive lookup. Tests.

- [x] T07 Web security and login
  - Public: landing, legal, help, login, password reset, static assets. Areas: `/admin/**` ADMIN; `/hr/**` HR or ADMIN; `/payroll/**` PAYROLL or ADMIN; `/finance/**` FINANCE or ADMIN; `/team/**` MANAGER; `/me/**` any signed-in employee.
  - Custom login page (email, password with show/hide, remember me), POST logout, landing by highest role (ADMIN -> /admin, HR -> /hr, PAYROLL -> /payroll, FINANCE -> /finance, else /me), `UserDetailsService`, disabled users rejected, CSP and headers from CLAUDE.md.
  - Tests: anonymous redirected; each role 403 outside its areas; multi-role user reaches each of its areas; landing by role.

- [x] T08 User administration
  - `/admin/users`: list with search and role filter; invite user (email + roles) sends a set-password link (token hashed, 48 h expiry, single use); edit roles; enable/disable. Never disable yourself or remove the last ADMIN.
  - `MailService` interface: `LoggingMailService` (dev/test) and `SmtpMailService` (mysql). Tests.

- [x] T09 Password reset, change password, lockout
  - Forgot password (same response whether the email exists), reset with a 30-minute single-use hashed token, change password (current required), lock for 15 minutes after 5 failed logins, friendly messages. Bootstrap the first ADMIN from `ADMIN_EMAIL`/`ADMIN_PASSWORD` when none exists. Tests.

- [x] T10 Audit log
  - `AuditEvent` (actor, action, entity type/id, summary, before/after JSON for changed fields with sensitive values masked, IP, timestamp) + `AuditService` used by later tasks; login success/failure and role changes recorded now.
  - `/admin/audit` with filters (actor, action, entity, date range), paginated, CSV export. Tests.

## Phase 2 - Company setup and statutory rules

- [x] T11 Company profile and pay settings
  - Company profile: legal name, brand name, address, PAN, TAN, PF establishment code, ESI employer code, PT registration (MH), logo upload, authorised signatory name and designation.
  - Pay settings: proration method, pay day, attendance cut-off day, default PF policy, gratuity provision in CTC (on/off), leave-encashment divisor, metro city list for HRA, compliance due days (PF 15, ESI 15, TDS 7, PT last day).
  - Pages under `/admin/company`; all changes audited. Tests.

- [x] T12 Organisation structure
  - CRUD for departments (with head), designations, grades, cost centres, work locations (name, address, state code, PT applicable). Cannot delete when in use (deactivate instead). Seed nothing yet (T20 does). Tests.

- [x] T13 Holiday calendar and work week
  - Per-location holiday list for each calendar year (national, festival, optional), work week pattern (e.g. Mon-Sat with 2nd/4th Saturday off), copy last year's list. Service answers "is working day?" and "working days between". Unit tests incl. 2nd/4th Saturday rule.

- [x] T14 Statutory rule tables
  - Effective-dated tables + entities for PF settings, ESI settings, PT slabs (state x gender x range, February amount, annual cap), tax regimes (slabs, standard deduction, rebate limit and max, cess, surcharge thresholds), old-regime deduction caps, gratuity settings.
  - A Flyway migration seeds exactly the values in `docs/STATUTORY-RATES.md` with `effective_from = 2026-04-01`.
  - `StatutoryRuleService.forDate(LocalDate)` returns one immutable snapshot of all rules in force.
  - `/admin/statutory`: read-only view of current rules with source notes and "last verified" date; "Add new version" form (effective date must be after the latest version; previous version auto-closed; audited). Tests including overlap rejection.

## Phase 3 - Employees

- [x] T15 Employee model
  - `Employee` entity + migration: code (`KSH-0001`, generated), names, gender, date of birth, personal email, mobile, addresses, emergency contact; joining date, confirmation date, employment type (PERMANENT, PROBATION, FIXED_TERM with end date, INTERN), status (ACTIVE, ON_NOTICE, EXITED), department, designation, grade, location, cost centre, reporting manager (self-reference); PAN (encrypted + hash), UAN, ESI IP number, PF policy, PT exempt flag + reason, person-with-disability flag; bank name, IFSC, account number (encrypted), account holder name.
  - Link to `User`. Validation per CLAUDE.md patterns. Repository queries with fetch joins. Tests.

- [x] T16 Employee directory and profile
  - `/hr/employees`: search (name, code), filters (department, location, status, type), sort, pagination, status chips, total count.
  - Profile with tabs: Overview, Job, Salary (only for salary roles), Statutory, Bank (masked), Documents, History. Tests for salary-tab visibility by role.

- [ ] T17 Add and edit employee
  - Multi-step form (Personal -> Job -> Statutory -> Bank -> Review) with per-step validation and draft kept in session; duplicate PAN/email rejected inline.
  - On save: generate code, optionally create the login (EMPLOYEE role) and send an invite. Edits audited (bank and PAN changes logged with masked values). Tests.

- [ ] T18 Bulk import
  - Download template (XLSX and CSV), upload, validate every row (required fields, patterns, references to departments/locations, duplicates), preview with row-level errors, import valid rows in one transaction, summary report. Tests with a mixed valid/invalid file.

- [ ] T19 Documents and lifecycle history
  - Upload documents per employee (type, file; pdf/jpg/png magic-byte check, 5 MB), download through an authorised controller only.
  - Lifecycle events: confirmation, transfer, promotion, manager change, recorded with effective dates and shown as a timeline. Tests including an employee downloading another employee's document -> 404.

- [ ] T20 Demo seed - company, organisation, employees, users
  - Runs when `app.seed-demo=true`, idempotent (test proves running twice changes nothing).
  - Company from CLAUDE.md with fictional registration numbers in valid formats; locations Shiroli factory, Rajarampuri head office, Pune sales office (all MH); departments Production, Quality, Maintenance, Stores, Sales, Accounts, HR & Admin, IT; designations and grades; 2026 holiday calendar.
  - 40 employees with realistic Maharashtrian and other Indian names: ~18 factory staff under the ESI ceiling, office staff above it, both genders (so PT slabs differ), one aged 58+, one fixed-term employee with 14 months' service, one with 6+ years' service, one person-with-disability flag, two without UAN.
  - The six demo users from CLAUDE.md linked to employees with matching roles (manager@ manages the Production team).

## Phase 4 - Salary

- [ ] T21 Salary components
  - Component master: code, name, type (EARNING, DEDUCTION, EMPLOYER_CONTRIBUTION, REIMBURSEMENT), fixed or variable, calculation (FIXED, PERCENT_OF_BASIC, PERCENT_OF_GROSS, BALANCE), flags: wage_core, prorated, taxable, esi_excluded, show_on_payslip, part_of_ctc, sort order.
  - Seed: Basic, DA, HRA, Conveyance, Special Allowance (BALANCE), Overtime, Bonus, Incentive, Arrears, Leave Encashment, Gratuity, Employer NPS; deductions PF, ESI, PT, TDS, Loan EMI, Other. CRUD in `/hr/salary/components` (system components not deletable). Tests.

- [ ] T22 Salary structure templates
  - Templates (e.g. Staff, Worker, Management) as ordered component rules; validation (exactly one BALANCE component, percentages sane). Preview a template at a sample CTC. Seed the W9 template ("Staff") plus "Worker". Tests.

- [ ] T23 Employee salary assignment and revisions
  - Assign a template + annual CTC with an effective date. Live htmx calculator shows monthly breakup, employer costs, monthly and annual CTC using the CTC solver; it reproduces both W9 rows.
  - 50% wage rule panel: shows wages vs gross and warns when the add-back applies.
  - Assignments and revisions are PENDING until approved by a different HR/ADMIN user (maker-checker); approved ones are effective-dated and never edited. Back-dated revisions into finalized months are flagged for arrears (T37). Salary history table. Audited. Tests incl. maker-checker.

- [ ] T24 Salary annexure PDF
  - CTC annexure / salary letter PDF (OpenPDF): company header and logo, employee details, monthly and annual breakup, employer contributions, effective date, signatory. Download from the salary tab and from self-service later. Test that the PDF opens and contains the key numbers (PDF text extraction).

## Phase 5 - Time and leave

- [ ] T25 Leave types and balances
  - Leave types with accrual rule, yearly quota, carry-forward cap, encashable, paid/unpaid, half-day allowed, gender restriction (optional). Seed per PAYROLL-RULES section 12.
  - Balance ledger (opening, accrual, taken, lapsed, encashed, adjustments with reason). Monthly accrual job (`@Scheduled`, uses Clock, idempotent per month) and year-end carry-forward. Tests.

- [ ] T26 Leave requests and approvals
  - Employee applies (date range, half days, reason); weekly offs and holidays excluded; balance and overlap checks; manager approves/rejects with comment; employee cancels before the start date (or requests cancellation after); notifications to both.
  - Team leave calendar for managers and HR. Tests incl. manager of another team -> 403.

- [ ] T27 Attendance
  - Daily attendance per employee (PRESENT, ABSENT, HALF_DAY, LEAVE, HOLIDAY, WEEKLY_OFF, WFH, ON_DUTY) auto-filled from holidays and approved leave.
  - HR monthly grid (employees x days) with htmx cell editing and colour legend; CSV import from a biometric export (code, date, in, out) with validation report.
  - Regularisation request by employee -> manager approval. Tests.

- [ ] T28 Monthly attendance close and overtime
  - Close a month: compute per employee days payable, LOP days, paid days and overtime hours; show exceptions (unmarked days, pending leave or regularisations); lock the month (reopening needs ADMIN, audited).
  - Overtime (for employee categories enabled in settings): amount = OT hours x 2 x (monthly wages / (26 x 8)), created as a variable earning for payroll. Tests.

## Phase 6 - Payroll engine

- [ ] T29 Payroll run model and workflow
  - `PayrollRun` (month, status DRAFT -> INPUTS_LOCKED -> CALCULATED -> SUBMITTED -> APPROVED -> FINALIZED -> PAID, plus REJECTED back to CALCULATED and REOPENED), one run per month, actor and timestamp per step, comments.
  - `PayrollWorkflow.transition(run, target, actor)` enforces transitions, roles and maker-checker (submitter cannot approve). Unit tests for every allowed and forbidden transition.

- [ ] T30 Payroll inputs
  - Inputs screen for the run: variable earnings (bonus, incentive, one-off allowance), one-time deductions, salary holds, new joiners and exits list, overtime from T28; add/edit inline with htmx; CSV import with validation report; inputs locked at INPUTS_LOCKED. Tests.

- [ ] T31 Calculator core
  - Pure-Java `PayrollCalculator`: input record (employee snapshot, salary assignment, attendance summary, variable inputs, rule snapshot, YTD figures) -> result record (lines per component, gross, wages, statutory, net, employer costs, exceptions).
  - Implements PAYROLL-RULES sections 1-4 and 11. Unit tests: W1, proration part of W8b, net pay rule, negative-net exception.

- [ ] T32 PF engine
  - `PfCalculator` per section 5, including policies, age 58+, NCP days, ECR wage columns. Unit tests W2a-W2e and PF lines of W8a/W8b.

- [ ] T33 ESI and PT engines
  - `EsiCalculator` per section 6 including contribution-period coverage (needs the fixed gross at period start), ₹176/day rule, rounding up. `PtCalculator` per section 7 incl. February and the annual cap. Unit tests W3a-W3e, W4, and the ESI/PT lines of W8b.

- [ ] T34 TDS engine
  - `TdsCalculator` per section 8 for both regimes: annual tax with rebate, marginal relief, surcharge with marginal relief, cess; projection; monthly regular + one-time method; previous-employer income/TDS.
  - Unit tests W5a-W5f, W6a-W6b, W7a-W7c, and that a full 12-month run collects exactly the annual tax by March.

- [ ] T35 Run payroll wizard and review
  - `/payroll/runs`: list of runs with status badges. Wizard: 1 Select month (attendance must be closed) -> 2 Pre-checks (missing bank details, missing PAN/UAN, unapproved salary revisions, employees without salary) -> 3 Inputs (T30) -> 4 Calculate -> 5 Review.
  - Review: register table (search, department filter, totals row), per-employee breakdown drawer, variance vs last month (> 10% highlighted with reason prompt), exceptions list that blocks submission, hold/release salary. Calculation of 500 employees completes in one request without N+1 (verified in T59). Tests.

- [ ] T36 Approval, finalize, reopen
  - Submit (PAYROLL) -> approve or reject with comment (FINANCE, not the submitter). Approval finalizes: immutable payslip snapshots (header + lines + rule version ids), YTD updated, leave encashment/loan balances updated, audit.
  - Reopen (ADMIN only, reason required): voids payslips of that run (kept, marked VOID) and returns to CALCULATED; regenerated payslips get version 2. Tests incl. submitter cannot approve and finalized numbers unchanged after a later salary change.

- [ ] T37 Arrears
  - Approved back-dated revisions create arrear lines in the next open run per section 9, with a month-by-month breakdown; PF difference recomputed with the ceiling; arrears treated as one-time for TDS. Unit and integration tests reproducing W10.

- [ ] T38 Loans and salary advances
  - Loan/advance records (amount, EMI, start month, reason, approval by HR), auto EMI deduction in payroll, skip-a-month with reason, balance statement, closure; outstanding balance exposed for F&F. Tests.

## Phase 7 - Outputs and compliance

- [ ] T39 Payslip PDF
  - OpenPDF payslip: company header and logo, month, employee details (code, name, designation, department, location, PAN, UAN, ESI number, bank masked, paid days, LOP), earnings and deductions side by side, reimbursements, net pay in figures and words, employer contributions (setting), YTD gross/TDS, leave balances, "computer-generated" footer.
  - Optional password protection (setting; password = date of birth DDMMYYYY). Test with PDF text extraction for W8a and W8b numbers.

- [ ] T40 Payslip distribution
  - After finalization: bulk generate, store, email each employee (MailService, attachment; progress shown), resend for one employee, regenerate (audited). Tests.

- [ ] T41 Bank file and payment
  - Bank transfer file for a finalized run: CSV (employee code, name, account, IFSC, amount, narration) and a generic NEFT bulk XLSX; held salaries excluded; total matches net pay; export audited.
  - Mark run PAID (FINANCE) with payment date and UTR (batch or per employee). Tests.

- [ ] T42 PF ECR and summary
  - ECR text file per finalized month: one line per member, fields separated by `#~#`: UAN, member name, gross wages, EPF wages, EPS wages, EDLI wages, EPF contribution (employee), EPS contribution, EPF-EPS difference (employer), NCP days, refund of advances. Members without UAN excluded and listed.
  - PF summary/challan view: employee share, EPS, employer EPF, EDLI, admin (company minimum ₹500 applied), totals and due date. Tests with an exact expected ECR line for W8b.

- [ ] T43 ESI and PT outputs
  - ESI monthly contribution XLSX: IP number, IP name, number of days, total monthly wages, reason code for zero days, last working day; ESI summary with both shares and due date.
  - PT (Maharashtra) monthly summary: slab-wise employee count and amount, total, due date. Tests.

- [ ] T44 TDS outputs
  - Monthly TDS register; quarterly salary TDS summary per employee (data for Form 138, labelled "Prepared for filing - verify before upload"); annual Form 130-style statement PDF per employee (income, deductions, tax computation, TDS by month) with the label from CLAUDE.md. Tests.

- [ ] T45 Reports
  - Payroll register XLSX (every component column, totals), department cost summary, CTC report, salary journal (expense and liability lines for accounting), variance report month vs month, headcount and attrition by month. Filters and downloads; on-screen tables use the data-table component. Tests.

- [ ] T46 Demo payroll history
  - Extend the seeder: close attendance and run the REAL engine for April-September 2026 for the demo company (finalized and paid), including one back-dated revision with arrears, one October-bonus-ready employee, loans with EMIs, and leave history. Idempotent. Test that dashboards and reports have data for all six months.

## Phase 8 - Self-service and managers

- [ ] T47 Employee home and payslips
  - `/me`: next pay date, last net pay, leave balances, pending requests, quick actions; payslip list with download; CTC breakup and annexure download; own profile (PAN in full, bank masked). Mobile-first. Tests incl. employee A cannot fetch B's payslip -> 404.

- [ ] T48 Tax regime and declarations (Form 124-style)
  - Regime choice with a side-by-side estimate (new vs old) from the employee's actual data; locked after HR closes the declaration window.
  - Declarations: rent (monthly rent, city, landlord name, landlord PAN required when annual rent exceeds ₹1,00,000), investments, health insurance, home-loan interest, previous employer income and TDS; proof uploads; HR verifies each item (approve, partly approve, reject with comment); approved values feed T34. Tests.

- [ ] T49 Reimbursement claims
  - Categories with monthly/annual limits and taxable flag (seed: fuel, phone and internet, medical, travel); claim with receipts; manager approval then FINANCE approval; approved claims become reimbursement lines in the next payroll. Tests.

- [ ] T50 Profile and bank change requests
  - Employee requests changes to address, mobile, emergency contact or bank details; HR approves or rejects; bank changes require a cancelled-cheque or passbook upload and are audited; payroll pre-check flags employees with pending bank changes. Tests.

- [ ] T51 Manager space and notifications
  - `/team`: team list (no salary data), pending approvals (leave, regularisation, reimbursements) with approve/reject inline, team calendar.
  - Notification entity + header bell (htmx poll every 30 s), `/notifications` with mark-all-read; emails for approval requests, payslip published, declaration window open/closing. Tests.

## Phase 9 - Exits

- [ ] T52 Separation
  - Resignation by employee or termination/retirement/end-of-contract initiated by HR; notice period from policy; last working day (early release allowed with reason); status ON_NOTICE; exit checklist (assets, handover, access removal) with owners. Tests.

- [ ] T53 Full and final settlement
  - Compute per section 13: final-month salary, leave encashment, gratuity (W11 rules), notice recovery or payout, loan recovery, TDS; editable adjustments with reason; approval by FINANCE; F&F statement PDF; due date = LWD + 2 working days with an overdue flag on dashboards; mark paid; employee status EXITED and login disabled after payment. Tests reproducing W11 and W12.

## Phase 10 - Dashboards and API

- [ ] T54 Dashboards and compliance calendar
  - HR/Payroll/Finance dashboards: headcount, joiners/leavers, this month's payroll status, payroll cost trend (12 months, server-side SVG), cost by department, statutory liabilities with due dates (PF, ESI, TDS, PT), overdue F&F, pending approvals. Admin dashboard: users, audit highlights, rule versions. Tests.

- [ ] T55 API security
  - Second `SecurityFilterChain` for `/api/**`: stateless, CSRF off, JWT bearer via Spring OAuth2 resource server, RSA keys (generated at startup in dev/test, from env in mysql). `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `GET /api/v1/me`. Errors as RFC 9457 ProblemDetail. Web chain unchanged. Tests.

- [ ] T56 Employee and manager API
  - Employee: payslips list + PDF download, CTC, leave types/balances/apply/cancel/list, attendance month view + regularisation, reimbursements claim (multipart) and list, tax regime and declaration status, notifications.
  - Manager: pending approvals list, approve/reject leave, regularisation and reimbursements. Record DTOs only; same services as the web. Tests incl. cross-employee access -> 404.

- [ ] T57 API documentation
  - springdoc-openapi at `/swagger-ui.html` with JWT auth, grouped (auth, employee, manager), example payloads. `docs/API.md` quick start for the app team. Test `/v3/api-docs` returns 200.

## Phase 11 - Polish and release

- [ ] T58 Responsive and accessibility audit
  - Review every template at 360 / 768 / 1280 px, focus order, labels, table semantics, contrast, keyboard dialogs and drawers. Fix everything and list the fixes in NOTES.md.

- [ ] T59 Performance
  - Test with 500 generated employees: payroll calculation for one month completes within 10 s on the test profile and with a bounded query count (Hibernate statistics). Fix any N+1 on directory, register and reports. Missing indexes via a new migration. Static assets with content-hash URLs and long cache headers.

- [ ] T60 Security review
  - IDOR tests on every employee-scoped resource (payslips, documents, declarations, claims, leave, attendance) and team-scoped resource; salary visibility by role; maker-checker on payroll, salary and bank changes; encryption at rest verified in the database; no sensitive values in logs; rate-limit login and password reset; CSP and headers; upload hardening; secure cookie flags in mysql. Fix all findings.

- [ ] T61 MySQL readiness
  - Review every migration for MySQL 8 compatibility. Add `docker-compose.yml` (MySQL 8) as an option. `docs/SETUP-MYSQL.md` for Windows. Note in NOTES.md that this needs one manual run by a human.

- [ ] T62 Documentation and demo script
  - README.md (overview, roles and features, stack, architecture Mermaid diagram, Windows setup, demo accounts, structure, screenshots section, team section placeholder).
  - `docs/ARCHITECTURE.md`, `docs/DATABASE.md` (Mermaid ER diagram + table notes), `docs/ROUTES.md` (every route and API endpoint with roles), `docs/USER-GUIDE.md` per role, and `docs/DEMO-SCRIPT.md`: a 10-minute viva walkthrough (employee -> manager approval -> HR -> payroll run -> finance approval -> payslip -> ECR -> F&F).

- [ ] T63 Final QA
  - Full build green. Every PAYROLL-RULES worked example has a passing test (list them in NOTES.md). No TODO / FIXME / lorem / "coming soon". Every route in `docs/ROUTES.md` is covered by at least one test. Consistent copy across pages. PLAN.md reconciled.
