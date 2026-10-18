# Payroll rules - formulas, rounding and worked examples

Rates come from `docs/STATUTORY-RATES.md` (stored in effective-dated tables). This file defines HOW they are applied.
Every example in section 14 must be a unit test with exactly these numbers. All examples were computed with an independent reference script before this file was written.

## 1. Rounding (project-wide)
| Amount | Rule |
|---|---|
| Prorated salary component | nearest rupee, HALF_UP, per component |
| PF employee, PF employer total, EPS, EDLI, admin | nearest rupee, HALF_UP; employer EPF = employer total - EPS |
| ESI employee and employer | round UP to the next whole rupee (ESIC practice) |
| Professional tax | slab amount (already whole rupees) |
| Annual income tax | compute with paise; round (tax + surcharge + cess) to the nearest rupee, HALF_UP |
| Monthly TDS | nearest rupee, HALF_UP, never below zero |
| Gratuity, leave encashment, notice pay | nearest rupee, HALF_UP |
| Net pay | whole rupees (follows from the rules above) |
Display uses two decimals (`₹38,000.00`).

## 2. Pay period, paid days and proration
- Pay period = calendar month. Payroll for month M is normally finalized and paid by the 7th of M+1; show a warning if the pay date is set later.
- Days payable = days in the month the person was employed (joining date to month end, or month start to last working day).
- Paid days = days payable - LOP days. LOP days come from the attendance close: absent without leave, LOP leave, half-day LOP = 0.5.
- Proration method (company setting, default `CALENDAR_DAYS`): prorated component = monthly amount x paid days / days in month. Options: `FIXED_30` (divide by 30), `WORKING_DAYS` (divide by working days in the month from the holiday calendar).
- Only components flagged `prorated` are prorated. Fixed one-time items are not.

## 3. Wages under the labour codes
Kosh applies the 50% test to the employee's **fixed monthly earnings for the month (after proration)**. One-time variable items (bonus, incentive, overtime, arrears) are excluded from the test. This is a project decision (section 15).
```
core        = Basic + DA + Retaining allowance            (components flagged wage_core)
gross_fixed = sum of fixed earning components for the month
excluded    = gross_fixed - core
wages       = core + max(0, excluded - 0.5 x gross_fixed)
```
`wages` is the base for PF and gratuity. It is not the base for ESI or PT.

## 4. Gross, reimbursements and CTC
- Gross earnings = prorated fixed earnings + variable earnings of the month (overtime, bonus, incentive, arrears).
- Reimbursements paid with salary are shown in their own payslip section. They are NOT part of gross, wages, PF, ESI or PT. Taxable reimbursements are added to taxable income.
- Monthly CTC = fixed monthly gross + employer PF total (EPS + EPF) + EDLI + PF admin + employer ESI + gratuity provision (optional company setting: 4.81% of Basic + DA).
- Annual CTC = 12 x monthly CTC + annual variable target.
- **CTC to structure solver:** given an annual CTC and a structure template, find the largest whole-rupee monthly gross whose monthly CTC is ≤ the target (bisection, then step up by ₹1). Earnings follow the template percentages (rounded per component); the balancing component (Special allowance) takes the remainder.

## 5. Provident Fund
Per employee: PF policy is one of `RESTRICT` (default), `ACTUAL`, `EXCLUDED`.
- `EXCLUDED` is allowed only if PF wages exceeded ₹15,000 at joining and the person has no UAN / prior PF membership.
```
pf_wages      = wages (section 3) of the month, plus wage arrears (section 9)
base          = min(pf_wages, ceiling) if RESTRICT else pf_wages          (ceiling = ₹15,000)
employee_pf   = round(12% x base)
employer_tot  = round(12% x base)
eps           = 0 if age >= 58 on the 1st of the month else round(8.33% x min(pf_wages, ceiling))
employer_epf  = employer_tot - eps
edli          = round(0.5% x min(pf_wages, ceiling))
admin         = round(0.5% x base)        (company total is at least ₹500 per month - applied in the PF summary, not per employee)
ncp_days      = LOP days of the month
```
ECR wage columns: EPF wages = base, EPS wages = min(pf_wages, ceiling) (0 if age ≥ 58), EDLI wages = min(pf_wages, ceiling), gross wages = gross earnings.

## 6. ESI
- Coverage is decided on **fixed monthly gross** (excluding variable items) at the start of each contribution period (1 Apr, 1 Oct), or at joining for new joiners: covered if ≤ ₹21,000 (₹25,000 if the employee is marked as a person with disability).
- Once covered, contributions continue until the end of that contribution period, even after a raise above the ceiling.
- ESI wages = gross earnings minus components flagged `esi_excluded` (annual bonus, leave encashment, gratuity). Overtime is included.
```
avg_daily   = esi_wages / paid_days
employee    = 0 if avg_daily <= 176 else ceil(0.75% x esi_wages)
employer    = ceil(3.25% x esi_wages)
```
- Zero paid days: no contribution; the ESI sheet needs a reason code.

## 7. Professional tax (Maharashtra)
- Slab lookup by work-location state (MH), gender and **gross earnings of the month** (reimbursements excluded).
- February uses the February amount (₹300 in the top slab). Employees marked PT-exempt pay nil.
- Annual cap ₹2,500 per tax year: never deduct more than (2,500 - PT already deducted this tax year).

## 8. Income tax (TDS on salary)
### 8.1 Annual tax for a given total income
```
slab_tax    = progressive tax on total income (regime slabs)
rebate      = new: if income <= 12,00,000 then min(slab_tax, 60,000)
              old: if income <= 5,00,000  then min(slab_tax, 12,500)
tax         = slab_tax - rebate
new regime marginal relief: if income > 12,00,000 then tax = min(tax, income - 12,00,000)
surcharge   = rate for the highest threshold crossed x tax
              marginal relief: tax + surcharge <= tax_at_threshold x (1 + previous_rate) + (income - threshold)
cess        = 4% x (tax + surcharge)
annual_tax  = round(tax + surcharge + cess)
```
### 8.2 Taxable income projection (each month, for the selected regime)
```
months_left  = months from the current month to March, inclusive (R)
projected    = taxable gross already paid this tax year by Kosh (finalized months)
             + previous-employer taxable salary (declared)
             + current month's regular taxable gross
             + (R - 1) x current full-month regular taxable gross (not prorated)
             + taxable reimbursements
new regime deductions: standard deduction 75,000; employer NPS up to 14% of (Basic + DA)
old regime deductions: standard deduction 50,000; professional tax; HRA exemption;
                       approved declarations within the configured caps
total_income = max(0, projected - deductions)
```
HRA exemption (old regime) = least of: HRA received; rent paid - 10% of (Basic + DA); 50% of (Basic + DA) for cities in the configured metro list, else 40%. All for the months rent is declared.
### 8.3 Monthly TDS
```
regular_tds = max(0, round((annual_tax(total_income without one-time items) - tds_already_deducted_this_year) / R))
one_time    = annual_tax(with this month's one-time items) - annual_tax(without them)      (deducted in full this month)
tds         = regular_tds + one_time
```
`tds_already_deducted_this_year` includes TDS declared from a previous employer. In March (R = 1) the formula collects the exact remaining balance.
Missing PAN is shown as a payroll exception (higher TDS for missing PAN is out of scope).

## 9. Arrears (back-dated salary revision)
- A revision effective in a month that is already finalized creates arrears in the next open payroll.
- For each finalized month affected: recompute that month with the revised salary and the same paid days. Arrears per component = revised - originally paid.
- Recompute PF for each arrear month (ceiling logic applies) and take the difference. ESI on arrears follows the coverage of the original month. Arrears are one-time items for TDS (section 8.3).
- Payslips show arrears as one line with a month-by-month breakdown in the detail view.

## 10. Loans and salary advances
- Interest-free in Kosh. EMI deducted every finalized month from the start month until the balance is zero; the last EMI equals the remaining balance.
- EMIs can be skipped for a month with an approval reason (balance and tenure extend). Outstanding balance is recovered in full and final.

## 11. Net pay
`net = gross - employee PF - employee ESI - PT - TDS - loan EMIs - other deductions + reimbursements`
- Net pay below zero is an exception: block submission until fixed (reduce or defer deductions).
- A held salary is calculated and finalized but excluded from the bank file until released.

## 12. Leave
- Accrual, carry-forward cap and encashment are set per leave type. Seed: Earned Leave 1.25 days per month of service (accrues at month end), carry-forward cap 45, encashable; Casual Leave 8 per year, not carried forward; Sick Leave 7 per year, carried forward up to 21; Loss of Pay (unpaid).
- Weekly offs and holidays inside a leave range are not counted.

## 13. Exit and full and final settlement
- Due date = last working day + 2 working days (holiday calendar). Dashboards flag overdue settlements.
- Pending salary for the final month: prorated as in section 2.
- Leave encashment = encashable leave balance x (Basic + DA) / 30 (divisor configurable).
- Notice pay recovery = shortfall days x monthly gross / 30. Notice pay in lieu (paid by the employer) uses the same formula.
- Gratuity = last month's wages (section 3, full month, not prorated) x 15/26 x years, where years = completed years + 1 if the final part-year is more than 6 months. Eligible after 5 years of continuous service, or 1 year for fixed-term employees. Capped at ₹20,00,000.
- Loan balance recovered in full. TDS on the settlement uses section 8.3 with encashment and gratuity as one-time items (taxability rules beyond this are out of scope and documented).

## 14. Worked examples (golden values - one unit test each)
### W1 - Labour code wages
| Case | Basic | DA | Gross fixed | Wages |
|---|---|---|---|---|
| W1a | 12,000 | 0 | 20,000 | 12,000 |
| W1b | 25,000 | 0 | 50,000 | 25,000 |
| W1c | 15,000 | 0 | 40,000 | 20,000 (excluded 25,000 exceeds 50% by 5,000) |

### W2 - Provident Fund
| Case | PF wages | Policy | Age | Base | Employee | EPS | Employer EPF | EDLI | Admin |
|---|---|---|---|---|---|---|---|---|---|
| W2a | 12,000 | RESTRICT | 30 | 12,000 | 1,440 | 1,000 | 440 | 60 | 60 |
| W2b | 25,000 | RESTRICT | 30 | 15,000 | 1,800 | 1,250 | 550 | 75 | 75 |
| W2c | 25,000 | ACTUAL | 30 | 25,000 | 3,000 | 1,250 | 1,750 | 75 | 125 |
| W2d | 20,000 | RESTRICT | 30 | 15,000 | 1,800 | 1,250 | 550 | 75 | 75 |
| W2e | 18,000 | RESTRICT | 58 | 15,000 | 1,800 | 0 | 1,800 | 75 | 75 |

### W3 - ESI (covered employees)
| Case | ESI wages | Paid days | Employee | Employer |
|---|---|---|---|---|
| W3a | 18,000 | 30 | 135 | 585 |
| W3b | 18,350 | 30 | 138 | 597 |
| W3c | 5,000 | 30 | 0 (₹166.67/day ≤ ₹176) | 163 |
| W3d | 14,857 | 26 | 112 | 483 |

W3e - coverage: fixed gross ₹20,500 on 1 April, raised to ₹22,000 from 1 July. The employee stays covered and contributes on actual wages for July-September, and is not covered from 1 October.

### W4 - Professional tax (Maharashtra)
| Gender | Gross of month | Month | PT |
|---|---|---|---|
| M | 7,500 | any | 0 |
| M | 9,000 | any | 175 |
| M | 25,000 | Sep | 200 |
| M | 25,000 | Feb | 300 |
| F | 24,000 | any | 0 |
| F | 30,000 | Sep | 200 |
| F | 30,000 | Feb | 300 |
A male employee above ₹10,000 every month pays 11 x 200 + 300 = 2,500 in the tax year.

### W5 - Annual tax, new regime (salary only, standard deduction 75,000)
| Case | Gross salary | Total income | Tax after rebate/relief | Surcharge | Cess | Annual tax |
|---|---|---|---|---|---|---|
| W5a | 12,75,000 | 12,00,000 | 0 | 0 | 0 | 0 |
| W5b | 13,00,000 | 12,25,000 | 25,000 (marginal relief; slab tax 63,750) | 0 | 1,000 | 26,000 |
| W5c | 18,00,000 | 17,25,000 | 1,45,000 | 0 | 5,800 | 1,50,800 |
| W5d | 60,00,000 | 59,25,000 | 13,57,500 | 1,35,750 | 59,730 | 15,52,980 |
| W5e | - | 51,00,000 | 11,10,000 | 70,000 (marginal relief; 10% would be 1,11,000) | 47,200 | 12,27,200 |
| W5f | - | 2,05,00,000 | 57,30,000 | 11,87,000 (marginal relief; 25% would be 14,32,500) | 2,76,680 | 71,93,680 |

### W6 - Annual tax, old regime (verify caps before demo)
| Case | Inputs | Total income | Annual tax |
|---|---|---|---|
| W6a | Gross 10,00,000; SD 50,000; PT 2,500; investments 1,50,000 | 7,97,500 | 74,880 (72,000 + cess 2,880) |
| W6b | Total income 5,00,000 | 5,00,000 | 0 (rebate 12,500) |

### W7 - Monthly TDS, new regime
- W7a: joins 1 April, ₹1,50,000 per month, no other income → annual tax 1,50,800 → April TDS = round(1,50,800 / 12) = **12,567**.
- W7b: same employee, Apr-Sep deducted 6 x 12,567 = 75,402. October bonus ₹1,00,000 (one-time). Annual tax with bonus 1,71,600, so one-time = 20,800. Regular = round((1,50,800 - 75,402) / 6) = 12,566. **October TDS = 33,366.** November: round((1,71,600 - 1,08,768) / 5) = **12,566**.
- W7c: joins 1 July at ₹1,00,000 per month, no previous employer → projected 9,00,000, total income 8,25,000 → annual tax 0 → TDS 0 every month.

### W8 - Full payslips
**W8a - Office employee, September 2026 (30 days), no LOP.** Female, Kolhapur, PF RESTRICT, age 31, new regime.
Basic 22,000 + HRA 8,800 + Special 9,200 = gross 40,000. Wages 22,000. PF: employee 1,800; EPS 1,250; employer EPF 550; EDLI 75; admin 75. ESI: not covered (fixed gross > 21,000). PT 200. TDS 0 (projected 4,80,000 → total income 4,05,000 → rebate).
**Net pay 38,000.** Monthly cost to company 41,950.

**W8b - Factory worker, February 2027 (28 days), 2 LOP days, paid days 26.** Male, ESI covered, PF RESTRICT, age 26.
Monthly: Basic 11,000, HRA 3,000, Special 2,000. Prorated: Basic 10,214, HRA 2,786, Special 1,857, gross 14,857. Wages 10,214.
PF: employee 1,226; EPS 851; employer EPF 375; EDLI 51; admin 51; NCP days 2. ESI: employee 112, employer 483. PT 300 (February). TDS 0.
**Net pay 13,219.**

### W9 - CTC to structure (template: Basic 50% of gross, HRA 40% of Basic, Special = balance; PF RESTRICT; gratuity provision off)
| Annual CTC | Monthly gross | Basic | HRA | Special | Employer PF | EDLI | Admin | Employer ESI | Monthly CTC |
|---|---|---|---|---|---|---|---|---|---|
| 6,00,000 | 48,050 | 24,025 | 9,610 | 14,415 | 1,800 | 75 | 75 | 0 | 50,000 |
| 2,40,000 | 18,222 | 9,111 | 3,644 | 5,467 | 1,093 | 46 | 46 | 593 | 20,000 |

### W10 - Arrears
Revision approved in September, effective 1 July: Basic 20,000 → 22,000, HRA 8,000 → 8,800, Special 8,000 → 9,200. July and August were finalized at the old salary with full paid days.
Arrears in the September run: Basic 4,000, HRA 1,600, Special 2,400 (total 8,000). PF difference = 0 (both old and new wages are above the ₹15,000 ceiling under RESTRICT). ESI: not covered. TDS: arrears are one-time income for September.

### W11 - Gratuity
| Case | Last wages | Service | Type | Gratuity |
|---|---|---|---|---|
| W11a | 30,000 | 6 y 7 m | permanent | 1,21,154 (7 years) |
| W11b | 18,000 | 1 y 2 m | fixed-term | 10,385 (1 year) |
| W11c | 30,000 | 4 y 8 m | permanent | 0 (not eligible) |
| W11d | 20,000 | 10 y 6 m | permanent | 1,15,385 (10 years; exactly 6 months does not round up) |

### W12 - Other settlement items and words
- Leave encashment: 12 days, Basic + DA 26,000 → 26,000 / 30 x 12 = **10,400**.
- Notice recovery: 15 days short, gross 45,000 → **22,500**.
- Amount in words (Indian system): 45,000 → "Rupees Forty-Five Thousand Only"; 1,23,456.50 → "Rupees One Lakh Twenty-Three Thousand Four Hundred Fifty-Six and Fifty Paise Only"; 2,05,00,000 → "Rupees Two Crore Five Lakh Only".

## 15. Project decisions
- The 50% wage test uses fixed monthly earnings only (section 3), not one-time variable pay.
- The CTC includes PF admin charges and EDLI (company setting; default on).
- Tax rounding is to the nearest rupee; statutory ₹10 rounding of income and tax is out of scope.
- Taxability of leave encashment and gratuity on exit is simplified to "taxable one-time income" unless a later task adds exemptions.
- Amount in words (2026-10-05, T03): always "Rupees <words> Only", tens hyphenated and no "and" inside the rupee part (W12); paise as " and <n> Paise" ("One Paisa" for 0.01), rounded HALF_UP to two decimals first; zero is "Rupees Zero Only"; crore counts above 99 are worded in the same system ("One Thousand Two Hundred Thirty-Four Crore"); negative amounts are rejected, so a recovery is worded as its positive amount next to a "recoverable" label.
- Pay settings (2026-10-09, T11): pay day and attendance cut-off are days of the month after the pay period. Pay day is the 2nd to the 28th (default 7th); a later pay day than the 7th is allowed but the settings page warns (section 2). The cut-off is the 1st to the 27th and must fall before pay day (default 3rd), which is why the 1st is not a pay day.
- Statutory due days (2026-10-09, T11): PF 15th, ESI 15th, TDS 7th, PT last day, each a day of the following month and editable. Any day from the 1st to the 30th or "last day of the month" (stored as 31) can be chosen; a day past the end of a shorter month means that month's last day. Weekends and holidays do not move a due date; the March TDS deposit date (30 April) is not modelled. The due days live only in the company pay settings (`company` table); the statutory rule tables (T14) do not hold them.
- Default PF policy (2026-10-09, T11): the company default for new employees is `RESTRICT` (seed) or `ACTUAL`. `EXCLUDED` is only ever set per employee, because its eligibility (section 5) is personal.
- HRA metro list (2026-10-09, T11): seeded with the eight 50% cities of the Income-tax Rules, 2026 (Delhi, Mumbai, Kolkata, Chennai, Bengaluru, Hyderabad, Pune, Ahmedabad; STATUTORY-RATES.md) and editable in pay settings (at most 20 names and 200 characters joined with commas, so the audit log keeps the whole list). Verify it before a demo together with the old-regime caps in STATUTORY-RATES.md.
- Leave encashment divisor (2026-10-09, T11): default 30 (section 13), accepted from 20 to 31.
- Working days (2026-10-11, T13): a working day at a work location is a day that is neither one of that location's weekly offs nor a national or festival holiday on its holiday calendar. Optional holidays are working days (someone who takes one off records leave). Weekly offs are set per location by weekday and week of the month, where the nth week is the nth occurrence of that weekday in the calendar month (the 2nd Saturday always falls on the 8th to the 14th; a 5th one only on the 29th to the 31st). A location without a saved work week has Sunday off. A holiday on a weekly off is not counted twice. `WORKING_DAYS` proration (section 2), leave ranges (section 12) and the settlement due date (section 13) use these answers (`HolidayService.isWorkingDay` / `workingDaysBetween`). The work week and the holiday list are not effective-dated: a change to either (including editing or deleting a past holiday) applies to every date, past and future, so a later count over an unfinalized past month, an arrears period or a leave range uses the list as it is now. Finalized payslips are protected only by their snapshots (they never recount). If a task needs past months counted with the pattern in force at the time, it adds `effective_from` to `work_weeks` in a new migration rather than relying on this. A festival or optional holiday copied from the previous year and not yet confirmed ("Check date") still counts on its copied date, which is probably wrong because those dates move: the payroll run's pre-checks (and any other count over a pay month) must flag a location that has unconfirmed holidays in that month before calculating.
- Statutory rule versions (2026-10-17, T14): each family (wages, PF, ESI, professional tax per state, new regime, old regime, old-regime deduction caps, gratuity) has its own timeline of versions. A version starts on the 1st of a month, and the income-tax families and the old-regime caps on 1 April, so one pay month (and one tax year) always has one set of rules. A new version must start after the family's latest version; that version is closed on the day before, and values are never edited. A version with exactly the latest values is refused (record a re-check with "Mark as checked" instead, which changes only the last-verified date and is audited). A back-dated start is allowed and applies to months not yet finalized; finalized payslips keep their snapshot. Once payroll runs exist (T31/T35), a new version must not start on or before the latest finalized pay month unless that month is reopened first: otherwise the version a finalized payslip records would be closed before the month it was used for (the values stay safe in the snapshot, but the audit picture would be wrong). A mistake in a version that has not started yet is fixed by withdrawing it (ADMIN, audited): only the latest version, only while it starts after today, and never a family's or state's only version. It is deleted with its slabs and the version before it has no end date again, so the same date can take the corrected values.
- Slab boundaries (2026-10-17, T14): a professional tax or income-tax slab covers amounts above the previous slab's limit up to and including its own limit; the top slab has no limit. So STATUTORY-RATES.md's "₹7,501 - ₹10,000" is read as "above ₹7,500 up to ₹10,000", and a gross of ₹7,500.50 pays ₹175. A surcharge applies once total income is above its threshold (section 8.1).
- ESI contribution periods (2026-10-17, T14): stored as the first period's starting month (4: April-September and October-March). The period a date falls in starts on the 1st of the nearest such month on or before it.
- Employee records (2026-10-18, T15): an employee is at least 14 on the joining date (nobody under 14 may be employed in any occupation under the Child and Adolescent Labour (Prohibition and Regulation) Act, 1986 as amended in 2016; whether a job is hazardous for someone under 18 is HR's call) and under 100 (a guard against a mistyped year). A fixed-term employee must have a contract end date after the joining date, an intern may have one, permanent staff and people on probation never do; a confirmation date is never before the joining date. `EXCLUDED` PF is refused for anyone with a UAN (section 5: a UAN means prior PF membership); the wage part of that test is checked once a salary exists (T23). A professional tax exemption needs a written reason. Bank account numbers are 9 to 18 digits. A reporting line never loops (nobody reports to themselves or to someone below them) and never starts at someone who has left (status EXITED); someone on notice can still be a manager. PAN, UAN, address, emergency contact and bank details are optional on the record (the payroll pre-checks flag what is missing); an address, emergency contact or bank account is stored complete or not at all.
- Gender and professional tax (2026-10-18, T15): an employee is recorded as male, female or transgender, the three options EPFO and ESIC records use. Maharashtra professional tax has women's slabs and general slabs (listed as Male in STATUTORY-RATES.md); the women's slabs are a concession for women, so a transgender employee pays on the general slabs. That is the conservative choice: it never deducts less than the general slabs require. Professional tax versions keep slabs for men and women only.
(Add new decisions here, with the date and the task ID.)
