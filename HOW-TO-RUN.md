# How to run the Kosh build loop (Windows 11)

## What's in this folder
| File | Purpose |
|---|---|
| `CLAUDE.md` | The rulebook: stack, architecture, roles, design system, security, testing. Claude Code loads it every session. |
| `PLAN.md` | 63 tasks in 12 phases, in dependency order, each with acceptance criteria. |
| `docs/STATUTORY-RATES.md` | PF, ESI, Maharashtra PT, income-tax and gratuity figures for tax year 2026-27, with sources. Seeded into the database. |
| `docs/PAYROLL-RULES.md` | Every formula and rounding rule, plus worked examples that become unit tests. |
| `PROMPT.md` | What ONE iteration does: pick the first open task, build it, test it, commit it, stop. |
| `NOTES.md` | Memory between iterations. |
| `loop.ps1` | Runs a fresh `claude -p` session per task until PLAN.md is done. |
| `.claude/settings.json` | Pre-approved commands, plus hard blocks (no git push/reset/clean, no `rm -rf`). |
| `.gitignore` | Keeps build output, logs, H2 data, uploads and `.env` out of git. |

## 1. Install once
1. **JDK 21 or newer** (Eclipse Temurin 21 LTS is fine). Check with `java -version`.
2. **Git for Windows**, then set your identity:
   ```powershell
   git config --global user.name "Your Name"
   git config --global user.email "you@example.com"
   ```
3. **Claude Code, up to date**: run `claude update`. The loop needs v2.1.259 or newer.
4. You do NOT need Maven, Node or MySQL to run the loop. MySQL is only for the final demo (step 7).

## 2. Set up the project folder
1. Create an empty folder on a local drive, e.g. `D:\projects\kosh` (not inside OneDrive).
2. Copy everything from this pack into it, including the hidden `.claude` folder, `.gitignore` and the `docs` folder.
3. Change the brand, package or demo company at the top of `CLAUDE.md` now if you want to. Renaming after T01 is painful.
4. **Check the rates.** Open `docs/STATUTORY-RATES.md` and spend 15 minutes confirming each figure against its source link. They were correct on 5 Oct 2026, but payroll rules change.
5. Open PowerShell in the folder and run `claude` once. Log in if asked, accept the "trust this folder" prompt, then type `/exit`.
6. If Windows blocks the script: `Unblock-File .\loop.ps1`. If it is still blocked, run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

## 3. Run it
```powershell
.\loop.ps1                      # runs until PLAN.md is done (max 80 sessions)
.\loop.ps1 -MaxIterations 5     # run only 5 tasks, then stop and review
.\loop.ps1 -Model opus          # force a model; recommended for Phase 6 (payroll engine)
```
Each iteration starts a fresh headless session. It reads PROMPT.md, takes the first open task, builds it, runs the full test suite, ticks the task, writes NOTES.md, and commits.

The script stops on its own when:
- no open tasks are left;
- 3 sessions in a row produce no commit (something is stuck: read `logs\` and NOTES.md);
- 5 sessions in a row fail with errors;
- you hit your usage limit. It then waits 30 minutes and retries, up to 6 hours.

Stop it any time with **Ctrl+C**. Re-run `.\loop.ps1` to resume. All state lives in PLAN.md, NOTES.md and git.

## 4. Check the app between phases
With the loop stopped:
```powershell
.\mvnw.cmd spring-boot:run
```
Open http://localhost:8080. After T20, sign in with the demo accounts (password `Demo@1234` for all):
`admin@kosh.test`, `hr@kosh.test`, `payroll@kosh.test`, `finance@kosh.test`, `manager@kosh.test`, `employee@kosh.test`.
Before T20 (or on a database without demo data) nobody can sign in as admin yet: set `$env:ADMIN_EMAIL` and
`$env:ADMIN_PASSWORD` (8 to 64 characters) before starting, sign in with them, then remove both. They only act while no
active admin exists.
After T46 there are six months of payroll history (April-September 2026), so dashboards and reports have data.
Press Ctrl+C to stop the server before restarting the loop.

## 5. Steer it
- **Wrong number?** Check `docs/PAYROLL-RULES.md` first. If the document is wrong, fix it, then add a task such as `- [ ] T64 Fix: ESI rounding per corrected rules` above the remaining open tasks.
- **Blocked task (`- [!]`)**: read the reason in NOTES.md, fix the cause, change the line back to `- [ ]`, re-run. Half-done code is in `git stash list`.
- **Denied command**: add it to `$allowedTools` in `loop.ps1` AND to `.claude/settings.json`.

## 6. Alternative: watch it work live (`/goal`)
```powershell
claude --permission-mode acceptEdits
```
Then paste:
```
/goal Follow PROMPT.md one task at a time and keep going until PLAN.md has no lines starting with "- [ ]". Before finishing, show a full ./mvnw verify run that ends in BUILD SUCCESS. Stop after 300 turns.
```
For long unattended runs, `loop.ps1` is the better choice: every task gets a fresh context.

## 7. MySQL for the final demo
T61 writes the full guide to `docs/SETUP-MYSQL.md`. Short version (use the exact names from `.env.example`, created in T02):
```powershell
$env:DB_URL="jdbc:mysql://localhost:3306/kosh"; $env:DB_USER="kosh"; $env:DB_PASSWORD="your-password"
$env:APP_DATA_KEY="<base64 32-byte key>"; $env:SEED_DEMO="true"
$env:APP_BASE_URL="http://localhost:8080"; $env:MAIL_FROM="payroll@example.com"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mysql"
```
Generate a key once in PowerShell:
```powershell
$b = New-Object byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```
Keep it safe. If you lose it, the encrypted PAN and bank fields can't be read.

## Reality check
- 63 tasks means 63+ long sessions. On a Pro plan expect repeated usage limits and a multi-day run; a Max plan makes it practical. Run it phase by phase and review.
- Phase 6 (payroll engine) is where mistakes are expensive. After T34, read the calculator code and tests yourself and check a few payslips by hand against `docs/PAYROLL-RULES.md`.
- The figures follow the 2026-27 rules researched on 5 Oct 2026. This is a degree project: do not use it to pay real people without a chartered accountant's review.
- In the viva you'll be asked how PF, ESI and TDS are calculated. Each team member should own one area (for example employees + salary, attendance + leave, payroll engine, compliance + API) and be able to walk through its code and tests.
- The loop builds and tests on H2. MySQL compatibility is reviewed in T61, but a human must run it once.
