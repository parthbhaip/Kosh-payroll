# Statutory rates - seed values

Researched on **5 October 2026**. T14 seeds these values into effective-dated tables with `effective_from = 2026-04-01` (the start of tax year 2026-27). Kosh holds no payroll before that date, so earlier rate history is not needed.
Rules change. Before any demo, a team member must re-check every row against the sources and add a new effective-dated version in Settings if anything changed. Kosh is a degree project, not certified payroll software.

## Labour codes (wage definition)
- The four labour codes came into force on **21 Nov 2025**. Kosh applies the Code on Wages definition of "wages" from that date.
- Wages = Basic + Dearness Allowance (DA) + Retaining Allowance. Excluded components (HRA, conveyance, special allowance, bonus, overtime, commission, employer PF and similar) may be at most **50%** of total remuneration. Any excess over 50% is added back to wages. Formula: PAYROLL-RULES.md section 3.
- Full and final settlement wages are due within **2 working days** of the last working day.
- Source: [India Briefing - 50% basic pay rule](https://www.india-briefing.com/news/salary-structure-india-labor-code-compliance-guide-44332.html), [PocketHRMS - F&F under the labour codes](https://www.pockethrms.com/blog/full-and-final-settlement/)

## Provident Fund (EPF, EPS, EDLI)
| Item | Value |
|---|---|
| Wage ceiling | ₹15,000 per month (unchanged since Sep 2014; re-notified under the Code on Social Security on 29 May 2026) |
| Employee share | 12% of PF wage base |
| Employer share | 12% of PF wage base, split: EPS 8.33% of min(PF wages, ₹15,000), remainder to EPF |
| EPS for members aged 58+ | Nil (full employer 12% goes to EPF) |
| EDLI (employer) | 0.5% of min(PF wages, ₹15,000) |
| EPF admin charges (employer) | 0.5% of EPF wages; minimum ₹500 per month per establishment |
| EDLI admin charges | Nil (waived from 1 Apr 2017) |
| Membership above the ceiling | Optional for new joiners whose PF wages exceed ₹15,000 and who were never PF members |
| Remittance due day (seed) | 15th of the following month |

Sources: [Indian Pay Calculator - ceiling stays ₹15,000 (29 May 2026 notification)](https://www.indianpaycalculator.in/govt-news/epf-wage-ceiling-stays-15000-ss-code-2026), [CA Club - admin charges 0.5% and EDLI admin waived](https://caclub.in/pf-admin-charges-reduced-and-edli-admin-charges-waived-by-epfo)

## Employees' State Insurance (ESI)
| Item | Value |
|---|---|
| Coverage ceiling | Gross wages ≤ ₹21,000 per month (₹25,000 for persons with disability) |
| Employee share | 0.75% of ESI wages |
| Employer share | 3.25% of ESI wages |
| Low-wage exemption | Average daily wage ≤ ₹176: employee share nil, employer share still payable |
| Contribution periods | 1 Apr - 30 Sep and 1 Oct - 31 Mar; once covered in a period, contributions continue to its end even if wages rise above the ceiling |
| Remittance due day (seed) | 15th of the following month |

No ceiling revision had been notified as of June 2026.
Source: [Bhatt & Joshi Associates - ESIC wage ceiling (2026)](https://bhattandjoshiassociates.com/esic-wage-ceiling-change-what-it-would-mean-for-employers/)

## Professional Tax - Maharashtra (state code MH)
Monthly salary means gross earnings for the month.

| Gender | Monthly salary | Monthly PT | February |
|---|---|---|---|
| Male | up to ₹7,500 | Nil | Nil |
| Male | ₹7,501 - ₹10,000 | ₹175 | ₹175 |
| Male | above ₹10,000 | ₹200 | ₹300 |
| Female | up to ₹25,000 | Nil | Nil |
| Female | above ₹25,000 | ₹200 | ₹300 |

Annual maximum ₹2,500. Employees can be marked PT-exempt with a reason (the law exempts some categories; Kosh keeps a flag and does not encode the categories).
Model PT as a table of state x gender x salary range, so other states can be added later. Only MH is seeded.
Source: [ClearTax - Professional Tax Maharashtra](https://cleartax.in/s/professional-tax-maharashtra)

## Income tax - tax year 2026-27 (Income-tax Act, 2025, in force from 1 Apr 2026)
The new regime is the default. Budget 2026 made no change to slabs, standard deduction, rebate, cess or surcharge.

### New regime
| Total income | Rate |
|---|---|
| up to ₹4,00,000 | Nil |
| ₹4,00,001 - ₹8,00,000 | 5% |
| ₹8,00,001 - ₹12,00,000 | 10% |
| ₹12,00,001 - ₹16,00,000 | 15% |
| ₹16,00,001 - ₹20,00,000 | 20% |
| ₹20,00,001 - ₹24,00,000 | 25% |
| above ₹24,00,000 | 30% |

- Standard deduction (salary): ₹75,000
- Rebate: up to ₹60,000 when total income ≤ ₹12,00,000, with marginal relief just above ₹12,00,000 (zero tax on salary up to ₹12,75,000)
- Surcharge: 10% above ₹50 lakh, 15% above ₹1 crore, 25% above ₹2 crore (capped at 25%), with marginal relief
- Health and education cess: 4% of (tax + surcharge)
- Employer NPS contribution deductible up to 14% of (Basic + DA)

### Old regime (optional, chosen by the employee)
Slabs (below 60 years): up to ₹2,50,000 nil; ₹2,50,001 - ₹5,00,000 5%; ₹5,00,001 - ₹10,00,000 20%; above ₹10,00,000 30%. Standard deduction ₹50,000. Rebate ₹12,500 when total income ≤ ₹5,00,000. Cess 4%. Surcharge 10% / 15% / 25% / 37% above ₹50 lakh / ₹1 crore / ₹2 crore / ₹5 crore.
**Verify before demo:** the deduction caps Kosh uses under the old regime are seed values carried over from the 1961 Act: ₹1,50,000 investments (old 80C), ₹25,000 health insurance for self/family under 60 (old 80D), ₹2,00,000 home-loan interest on a self-occupied house, HRA exemption, and professional tax. Section numbers changed under the 2025 Act; store them as configurable caps, not section numbers in code.

### Forms (names only - Kosh produces "style" documents, not official filings)
- Employee declaration of deductions: **Form 124** (replaces Form 12BB from tax year 2026-27)
- Annual TDS certificate for salary: **Form 130** (replaces Form 16 from tax year 2026-27; Parts A, B, C)
- Quarterly salary TDS return: **Form 138** (replaces Form 24Q)
- TDS deposit due day (seed): 7th of the following month (configurable)

Sources: [Bhatt & Joshi - Budget 2026-27 income tax changes](https://bhattandjoshiassociates.com/indian-union-budget-2026-27-key-income-tax-changes-and-their-impact-on-salaried-individuals/), [TaxGuru - new regime slabs](https://taxguru.in/?p=1024891), [TaxHeal - FY 2026-27 slabs (old regime)](https://www.taxheal.com/income-tax-slab-for-fy-2026-27.html), [SCC Online - Form 130 vs Form 16](https://www.scconline.com/blog/post/2026/06/15/overview-comparison-of-form-16-and-form-130-tds-certificate/), [Indian Pay Calculator - Form 124](https://www.indianpaycalculator.in/form-124-form-12bb)

## Gratuity
- Eligibility: 5 years of continuous service; **1 year** for fixed-term employees (Code on Social Security).
- Amount = last drawn wages (as defined above) x 15/26 x completed years of service; a final part-year of more than 6 months counts as a full year.
- Ceiling: ₹20,00,000 (configurable).
- Source: [PocketHRMS - gratuity under the Code on Social Security](https://www.pockethrms.com/blog/full-and-final-settlement/)

## Out of scope (documented, not built)
Maharashtra Labour Welfare Fund, statutory bonus computation, higher TDS for missing PAN (flagged as an exception instead), states other than Maharashtra for PT, the official FVU/ECR portal upload itself, and the ₹10 rounding of income and tax.
