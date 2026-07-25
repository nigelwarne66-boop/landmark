# Landmark Design System

**Status:** v1.0 — canonical. Supersedes any styling decisions made ad hoc in either app.
**Applies to:** `landmark` (JavaFX desktop, port 8090) and `landmark-reports` (Spring Boot + Thymeleaf, port 8091).
**Audience:** Claude Code (implementation), Nigel (approval).
**Spelling:** Australian English throughout — colour, favourites, organise, licence (noun) / license (verb), enquiry.

---

## 0. How to use this document

This is the single source of truth for visual and interaction decisions across both applications. Two token files ship alongside it:

| File | Target | Location in repo |
|---|---|---|
| `landmark-tokens.css` | Reporting suite (web) | `landmark-reports/src/main/resources/static/css/landmark-tokens.css` |
| `landmark-theme.css` | Desktop (JavaFX) | `landmark/src/main/resources/com/landmarksoftware/ui/css/landmark-theme.css` |

**Rule for Claude Code:** never introduce a raw hex value, px size, or font name in component CSS. Every value comes from a token. If a needed token doesn't exist, add it to the token file and note it in §11 (Change log), don't inline it.

---

## 1. Who this is for, and what that means

Landmark's users are bookkeepers, accountants and office managers at Australian mid-market businesses, most of them migrating off a COBOL green-screen system they have used for fifteen years. They work in the software all day. They type faster than they click. They care about the number being right and being able to prove where it came from.

That produces five principles, in priority order:

1. **Density with air.** More rows on screen than Xero, more whitespace than the COBOL screens. Row height 32px, not 48px. Padding measured in 4px steps, never guessed.
2. **Chrome is quiet; data is loud.** The shell (header, rail, cards) is neutral and low-contrast. Colour is reserved for meaning — status, ageing, dr/cr, warnings. If a colour on screen doesn't mean something, it's wrong.
3. **Every figure is a door.** Any amount, count or code is a drill-down target. Consistent hover and click behaviour on numbers is a core promise, not a nice-to-have.
4. **Keyboard first.** A user must be able to complete entry without the mouse. Focus is always visible. Shortcuts are discoverable, not folklore.
5. **Say what happens.** Plain-English labels in the user's vocabulary. "Post journal", not "Submit". The button, the confirmation and the toast all use the same word.

---

## 2. What we take from the competition

A short, honest audit. These are pattern-level observations, not claims about any current release.

| Product | What works | What we take |
|---|---|---|
| **Xero** | Calm single-accent chrome, generous whitespace, plain-English labels and subtitles, very low visual noise | Sentence-case plain-English naming; one accent only; a short descriptive subtitle under every page title |
| **MYOB** | Left module rail matching the accountant's mental model; workflow-oriented landing screens | Module rail as permanent primary navigation; module landing pages that show the workflow, not just a link list |
| **Dynamics 365 Business Central** | Persistent action bar on every list and card page; role centre with live "cues"; genuinely keyboard-driven; high data density | The **command bar** pattern (§7.4); the **module dashboard with cues** (§7.10); density and keyboard parity |
| **QuickBooks** | Fast global create; unambiguous status pills on every transaction | Status chip vocabulary (§7.8) and a single global "New" affordance |
| **Sage Intacct / NetSuite** | Saved views and filters; drill-down from any total to its components | Saved report parameters and favourites; drill-down contract (§7.7) |

**Where we differ deliberately.** None of them serve a customer running a supervised batch-entry workflow, which Landmark keeps as a control feature. The Draft → Ready → Posted lifecycle needs first-class visual language (§7.8) that competitors don't have because they've abandoned the model. That's an advantage — design it well and it becomes the reason a conservative finance manager chooses Landmark.

---

## 3. Audit of the current screens

### Desktop (screenshot 1)

| # | Defect | Fix |
|---|---|---|
| D1 | Nav icons render as empty boxes — Ikonli glyphs not resolving | Bundle `tabler-pack`; verify at startup; see §5.2 |
| D2 | Nav is missing Purchasing, Inventory, Fixed Assets | Use the module registry in §4 verbatim |
| D3 | "Sign Out" is a red outlined button — red must mean destructive or error only | Move into user menu popover (§7.2) |
| D4 | Every list item is grey with a "soon" tag; the whole page reads as broken/disabled | Items render at normal weight and colour; the badge alone carries the state (§7.9) |
| D5 | Stray olive/cream band below the content area | Content region fills to viewport edge with `--lm-surface-app` |
| D6 | Logo sits in the rail here, in the header in Reports | Logo in the **header** in both apps |
| D7 | Two large cards with different internal list styles | One card pattern (`ModuleCard`) plus one row pattern (`ListRow`) — nothing else |
| D8 | Global search sits centred in the header at ~200px wide, visually adrift | Right-aligned search group, 320px, per §5.1 |

### Reporting suite (screenshot 2)

| # | Defect | Fix |
|---|---|---|
| R1 | "Business Activity Statement" vs desktop's "BAS / Tax" | Module names come from §4 and are identical in both apps |
| R2 | Report search lives in the rail; desktop's search lives in the header | Rail carries navigation only. List filter moves into the content area above the list (§7.6) |
| R3 | Dotted browser-default focus ring on "Switch company" | Token focus ring, 2px `--lm-focus`, 2px offset, on every focusable element |
| R4 | Report row icon tiles are all near-identical mint | One accent per module, from §4, on the tile only |
| R5 | "NIGEL" as bare text, no avatar, no menu | Same user menu component as desktop |
| R6 | Rail item heights and type sizes differ from desktop | Shared shell spec, §5 |
| R7 | Favourites exists here but not on desktop | Favourites is a shell feature in both apps |

---

## 4. Module registry — single source of truth

Nav order is locked. Names are locked. Do not abbreviate, expand or reorder in either app.

| # | Module | Code | Icon (Tabler) | Accent | Tile tint | Desktop | Reports |
|---|---|---|---|---|---|---|---|
| 1 | General Ledger | GL | `book-2` | `#3D5A98` | `#E8EBF3` | ✓ | ✓ |
| 2 | Accounts Receivable | AR | `file-invoice` | `#2E7D57` | `#E6EFEB` | ✓ | ✓ |
| 3 | Accounts Payable | AP | `receipt-2` | `#B26B12` | `#F6EDE3` | ✓ | ✓ |
| 4 | Cash Management | CM | `wallet` | `#0E7263` | `#E2EEEC` | ✓ | ✓ |
| 5 | Purchasing | PO | `shopping-cart` | `#6B4FA8` | `#EDEAF5` | ✓ | ✓ |
| 6 | Inventory | SM | `packages` | `#2F6F8F` | `#E4EEF2` | ✓ | ✓ |
| 7 | Fixed Assets | FA | `building-warehouse` | `#8A5A3C` | `#F1EBE8` | ✓ | ✓ |
| 8 | Payroll | PY | `users` | `#A63D6B` | `#F4E8ED` | ✓ | ✓ |
| 9 | BAS / Tax | BAS | `percentage` | `#5F7A2E` | `#ECEFE6` | ✓ | ✓ |
| 10 | System | SY | `settings` | `#5A5F5B` | `#EBECEB` | ✓ | — |

The accent is used **only** in the module's icon tile, the module dashboard page-header rule, and chart series defaults. It never colours the rail, header, buttons or text.

Implementation: one registry, not two lists.
- Desktop: `com.landmarksoftware.ui.nav.Module` enum — name, code, tabler icon literal, accent hex, tint hex, route.
- Reports: `com.landmarksoftware.reports.nav.Module` enum with identical members, exposed to Thymeleaf via a `@ModelAttribute("modules")` on a `@ControllerAdvice` so every template renders the same rail.

---

## 5. The shell

Identical geometry in both apps. A user switching between the desktop app and the reporting suite should not notice the boundary except in the content area.

```
┌────────────────────────────────────────────────────────────────────────┐
│ ▣ Landmark   Demo Pty Ltd · FY 2025–26      [search 320]   [NW ▾]      │ 56
├──────────────┬─────────────────────────────────────────────────────────┤
│ MODULES      │  Accounts Receivable                       [New ▾]      │
│ ▌ General L… │  Customer invoicing, debtors and receipts               │
│   Accounts … │  ─────────────────────────────────────────────────────  │
│   Accounts … │                                                          │
│   Cash Man…  │  ┌── content ──────────────────────────────────────┐    │
│              │                                                          │
│ 240          │  32px padding, fluid width                              │
└──────────────┴─────────────────────────────────────────────────────────┘
```

### 5.1 Header — 56px

Single row, `--lm-surface-raised` background, 1px `--lm-border-subtle` bottom border, no shadow. Spans full width in both apps; the rail starts below it.

Left group (16px from edge, 12px internal spacing):
- Brand mark, 24×24
- Wordmark "Landmark", `--lm-text-primary`, 15px/600, tracking -0.01em
- Context chip: `Demo Pty Ltd · FY 2025–26` — 13px, `--lm-text-secondary`, in a pill of `--lm-surface-sunken`, clickable, opens the company/year switcher. This replaces the separate "Switch Company" button in both apps. It is the most-checked fact on the screen; make it a control, not a label.

Right group (16px from edge, 8px internal spacing):
- Search field, 320px, `search` icon inside, placeholder "Search…" (desktop) / "Search reports…" is **not** used here — see §7.6
- User button: 28px circular avatar with initials on `--lm-surface-sunken`, name at 13px, `chevron-down`. Opens the menu in §7.2.

### 5.2 Navigation rail — 240px expanded, 64px collapsed

- Background `--lm-surface-rail`, 1px right border `--lm-border-subtle`.
- Section label: "MODULES", 11px/600, tracking 0.06em, uppercase, `--lm-text-muted`, 16px top padding, 12px left.
- Item: 36px high, 8px left/right inset, 6px radius, icon 18px at `--lm-text-secondary`, label 14px/500.
- Hover: `--lm-surface-hover` background.
- **Active (the ledger spine): 3px `--lm-brand-600` bar flush to the item's left edge, background `--lm-brand-050`, icon and label `--lm-brand-700`, label weight 600.**
- Favourites sits above MODULES with a `star` icon and a count badge, in both apps.
- Collapse toggle at rail bottom; collapsed state shows icons only with tooltips. Persist the choice per user.

### 5.3 Content region

- Background `--lm-surface-app`, padding 32px (24px below 1280px viewport width).
- No max-width on list/table pages. Forms constrain to 720px; two-column forms to 960px.
- **Page header** on every screen: title 24px/600 tracking -0.02em; subtitle 14px `--lm-text-secondary` on the line below; right-aligned primary action; 1px `--lm-border-subtle` rule 20px beneath, inset 0. On module dashboards the rule takes the module accent at 2px for its first 48px, then hairline — the ledger spine again, horizontal.

---

## 6. Tokens

### 6.1 Colour

**Surfaces** — warm neutral, inherited from the existing screens, cooled slightly so the accent stays clean.

| Token | Hex | Use |
|---|---|---|
| `--lm-surface-app` | `#F6F6F3` | Content region background |
| `--lm-surface-raised` | `#FFFFFF` | Cards, header, table body, inputs |
| `--lm-surface-rail` | `#FBFBF9` | Navigation rail |
| `--lm-surface-sunken` | `#EFEFEA` | Chips, avatars, table header, disabled fields |
| `--lm-surface-hover` | `#F0F0EC` | Row and nav hover |
| `--lm-surface-selected` | `#E9EFEC` | Selected table row |
| `--lm-border-subtle` | `#E5E5DF` | Dividers, card borders, table rules |
| `--lm-border-default` | `#D3D3CB` | Input borders |
| `--lm-border-strong` | `#B5B5AB` | Input hover, focused separators |

**Text**

| Token | Hex | Use |
|---|---|---|
| `--lm-text-primary` | `#1A1D1B` | Headings, values, body |
| `--lm-text-secondary` | `#5A5F5B` | Subtitles, labels, secondary rows |
| `--lm-text-muted` | `#868C87` | Placeholders, meta, section labels |
| `--lm-text-inverse` | `#FFFFFF` | On brand and semantic fills |

**Brand — Landmark teal.** Deliberately not Xero blue, MYOB purple or QuickBooks green.

| Token | Hex | Use |
|---|---|---|
| `--lm-brand-800` | `#08483F` | Pressed |
| `--lm-brand-700` | `#0B5F53` | Hover, brand text on light |
| `--lm-brand-600` | `#0E7263` | Primary fill, active spine |
| `--lm-brand-300` | `#7FBDB2` | Charts, decorative |
| `--lm-brand-100` | `#D6E9E5` | Tile fills |
| `--lm-brand-050` | `#EDF5F3` | Active nav background |

**Semantic** — meaning only, never decoration.

| Token | Fill | Tint | Meaning |
|---|---|---|---|
| `--lm-success` | `#1E7B45` | `#E4F1E9` | Posted, reconciled, paid |
| `--lm-warning` | `#A8630A` | `#FAEEDD` | Ready to post, due soon, needs attention |
| `--lm-danger` | `#B3261E` | `#FAE9E7` | Overdue, failed validation, destructive |
| `--lm-info` | `#1B5E9C` | `#E5EEF7` | Draft, informational |
| `--lm-focus` | `#14887A` | — | Focus ring |

**Financial semantics**

| Token | Hex | Use |
|---|---|---|
| `--lm-amount-negative` | `#B3261E` | Negative amounts, when the red-negatives preference is on |
| `--lm-ageing-current` | `#5A5F5B` | Current bucket |
| `--lm-ageing-30` | `#7A7A4E` | 30 days |
| `--lm-ageing-60` | `#A8630A` | 60 days |
| `--lm-ageing-90` | `#9A4218` | 90 days |
| `--lm-ageing-90plus` | `#B3261E` | 90+ days |

Negatives render in parentheses by default — `(1,234.56)` — which is what the COBOL reports do and what the users expect. Red is a per-user preference layered on top, never a replacement for the parentheses.

### 6.2 Typography

**IBM Plex Sans** for interface, **IBM Plex Mono** for numerals and codes. Both are SIL Open Font Licence, so they can be bundled and redistributed with a commercial product without the licensing exposure that comes with most alternatives — the same reasoning that led to Liberica for the JDK.

The pairing is also the right one for the subject. Plex Mono in amount columns, document numbers, account codes and journal references gives the figures the ruled-column rhythm of a ledger and reads as continuous with the system these users are leaving, without being a green-screen pastiche. Fixed advance width means digits align down a column with no tabular-figures workaround — which matters because JavaFX has no `font-variant-numeric`.

Fallback stack (web): `"IBM Plex Sans", "Segoe UI Variable Text", "Segoe UI", system-ui, sans-serif`.
Desktop: load both families with `Font.loadFont` at startup from `resources/fonts/`; fall back to Segoe UI.

| Token | Size / line | Weight | Tracking | Use |
|---|---|---|---|---|
| `--lm-type-display` | 24 / 32 | 600 | -0.02em | Page title |
| `--lm-type-title` | 18 / 26 | 600 | -0.01em | Card and section headings |
| `--lm-type-subtitle` | 15 / 22 | 500 | 0 | Card subheads |
| `--lm-type-body` | 14 / 20 | 400 | 0 | Base |
| `--lm-type-body-strong` | 14 / 20 | 600 | 0 | Emphasis, active nav |
| `--lm-type-label` | 13 / 18 | 500 | 0 | Field labels, table headers, nav |
| `--lm-type-caption` | 12 / 16 | 400 | 0 | Meta, help text |
| `--lm-type-micro` | 11 / 14 | 600 | 0.06em | Section labels (uppercase), badges |
| `--lm-type-num` | 14 / 20 | 400 | 0 | Plex Mono — amounts, codes |
| `--lm-type-num-strong` | 14 / 20 | 600 | 0 | Plex Mono — totals |

### 6.3 Spacing, radius, elevation, motion

4px base. `--lm-space-1` 4 · `-2` 8 · `-3` 12 · `-4` 16 · `-5` 20 · `-6` 24 · `-7` 32 · `-8` 40 · `-9` 48 · `-10` 64.

Radius: `--lm-radius-sm` 4 (badges, chips, inputs inside tables) · `-md` 6 (buttons, inputs, nav items) · `-lg` 10 (cards, dialogs) · `-pill` 999.

Elevation — this is a border-first system; shadows are for things that float over content only.

| Token | Value | Use |
|---|---|---|
| `--lm-elev-0` | none | Cards, header, rail — use borders |
| `--lm-elev-1` | `0 1px 2px rgba(20,24,26,.06)` | Hovered card, sticky table header |
| `--lm-elev-2` | `0 4px 12px rgba(20,24,26,.10)` | Popovers, dropdowns, toasts |
| `--lm-elev-3` | `0 12px 32px rgba(20,24,26,.16)` | Dialogs |

Motion: `--lm-motion-fast` 120ms `cubic-bezier(.2,0,.2,1)` for hover, press, focus. `--lm-motion-base` 160ms for popovers and expansion. Nothing longer, nothing bouncy, no entrance animation on data. Respect `prefers-reduced-motion` on web; on desktop, gate transitions behind a `landmark.ui.reducedMotion` preference.

---

## 7. Components

### 7.1 Buttons

| Variant | Fill | Border | Text | Use |
|---|---|---|---|---|
| Primary | `--lm-brand-600` | none | inverse | One per screen — the main action |
| Secondary | `--lm-surface-raised` | 1px `--lm-border-default` | primary | Everything else |
| Ghost | transparent | none | secondary | Toolbar and row actions |
| Danger | `--lm-danger` | none | inverse | Delete, reverse, cancel a posted document |

Heights: 32px default, 28px in dense toolbars, 36px on forms. Padding 12px horizontal, 8px between icon and label. Radius `--lm-radius-md`. Disabled: 40% opacity, no colour change. **Danger is never used for navigation or sign-out.**

Labels are verbs in sentence case: "Post journal", "Save changes", "Run report", "Add customer". The confirmation and the toast reuse the same verb.

### 7.2 User menu

Replaces the loose text/buttons in both apps. Trigger is the avatar + name in the header. Popover, 240px, `--lm-elev-2`:

```
NIGEL WARNE
nigel@…                      ← 12px muted
──────────────────
Switch company
Switch financial year
Preferences
──────────────────
Sign out
```

"Sign out" is a normal menu item with a `logout` icon. Not red, not a button.

### 7.3 ModuleCard

The card used on module landing pages. 10px radius, 1px `--lm-border-subtle`, white, 20px padding, no shadow at rest, `--lm-elev-1` and `--lm-border-strong` on hover.

Header row: 36px icon tile (module tint background, module accent icon, 8px radius), then title (`--lm-type-title`) and one-line description (`--lm-type-caption`, muted). Optional "Open ›" link right-aligned, `--lm-brand-700`.

Body: `ListRow` items, full-bleed to the card edge, separated by 1px `--lm-border-subtle`.

Grid: `repeat(auto-fill, minmax(340px, 1fr))`, 20px gap.

### 7.4 CommandBar

Borrowed from Business Central. A 44px bar directly under the page header on every list and record screen: left-aligned action buttons (primary first, then secondary, then ghost overflow under a `dots` menu), right-aligned view controls (filter, density toggle, export). Background `--lm-surface-app`, no border, buttons at 28px.

This is what makes the app feel like a system of record rather than a website. Implement it once, use it everywhere.

### 7.5 ListRow

The single row pattern for report lists, record lists and card bodies. 52px minimum height, 16px horizontal padding, 1px bottom border, hover `--lm-surface-hover`, whole row clickable.

Layout: `[32px icon tile] [title 14/500 + description 12 muted] ……… [meta] [trailing control]`

Trailing control is the favourite star in the reporting suite (filled `--lm-warning` when set, outlined `--lm-text-muted` when not) and a `chevron-right` elsewhere. Never both.

### 7.6 Search and filter — two distinct things

- **Global search** lives in the header, in both apps. Searches records and screens. `Ctrl+K` opens it from anywhere.
- **List filter** is not search. It sits in the CommandBar of the screen it filters, 240px, with a `filter` icon and placeholder naming the thing being filtered — "Filter reports…", "Filter customers…". Move the reporting suite's rail search here. The rail carries navigation and nothing else.

### 7.7 DataTable

The most important component in the product.

- Header: `--lm-surface-sunken`, `--lm-type-label`, 36px, sticky, 1px bottom `--lm-border-default`.
- Rows: 32px comfortable / 28px compact (user toggle, persisted). No zebra striping — a 1px `--lm-border-subtle` rule between rows is enough and reads as ruled ledger paper.
- Hover `--lm-surface-hover`; selected `--lm-surface-selected` with the 3px `--lm-brand-600` spine on the left edge.
- Text columns left-aligned. **Amount columns right-aligned, `--lm-type-num`, 16px right padding.** Dates in `dd/mm/yyyy`, Plex Mono, left-aligned. Codes in Plex Mono.
- Totals row: sticky to the bottom, `--lm-surface-sunken`, 2px top border `--lm-border-strong`, `--lm-type-num-strong`.
- **Drill-down contract:** any cell that drills shows `--lm-brand-700` text on row hover and a pointer cursor. Nothing that doesn't drill ever does this. A drill-down opens the detail with a breadcrumb back to the figure it came from.
- Empty state: centred, 32px module icon at 40% opacity, one line of what's missing, one primary action. "No transactions in this period. Change the date range or post a journal."

### 7.8 StatusChip

Pill, `--lm-type-micro` (not uppercase — sentence case), tint background, semantic text colour, 20px high, 8px horizontal padding.

| Status | Token | Where |
|---|---|---|
| Draft | info | Posting lifecycle |
| Ready | warning | Posting lifecycle |
| Posted | success | Posting lifecycle |
| Open | info | AR/AP `trx_status = O` |
| Paid | success | AR/AP `trx_status = P` |
| Overdue | danger | Derived from `due_date` |
| Outstanding | info | CM `trx_status = O` |
| Reconciled | success | CM `trx_status = R` |
| Cancelled | neutral (`--lm-surface-sunken` / `--lm-text-muted`) | PO `po_status = X` |
| Inactive | neutral | Any master file where status ≠ A |

The Draft → Ready → Posted triple is Landmark's differentiator. On any record showing it, render the three states as a small horizontal progress indicator, not just a single chip, so a supervisor can see at a glance where a batch sits.

### 7.9 Badge

Small neutral marker for metadata: `--lm-surface-sunken` background, `--lm-text-muted`, `--lm-type-micro`, 4px radius, 16px high. Used for "soon", counts, record types.

**A badge never changes the styling of the thing it labels.** Fixes defect D4: unbuilt items keep normal text colour and weight, with a "soon" badge beside them and the row non-interactive with `cursor: default`. The screen then reads as a roadmap, not a fault.

### 7.10 Module dashboard cues

Each module landing page opens with a row of up to four cue tiles: a large Plex Mono figure (`--lm-type-num` at 28px/600), a label beneath, and a trend or ageing indicator. Each tile drills into the list it summarises. Examples: AR → Total debtors, Overdue, Invoiced this period, Receipts this period. Cue data comes from the pre-aggregated tables (`glbal`, `arsales`, `apsumry`, `pacosts`, `smsumry`) rather than scanning transaction tables.

### 7.11 Forms

Label above field, `--lm-type-label`, `--lm-text-secondary`, 6px gap. Fields 32px high, 1px `--lm-border-default`, 6px radius, white. Hover `--lm-border-strong`. Focus: 1px `--lm-focus` border plus 2px ring at 40% alpha.

Field widths follow the data, not the grid — an account code field is not full width. Amount and code inputs use Plex Mono, right-aligned for amounts. Required fields marked with a `*` after the label; optional fields carry no marker. Errors sit beneath the field in `--lm-danger` at `--lm-type-caption`, and state what to do: "Enter a date within FY 2025–26."

---

## 8. Voice

Sentence case for everything except the `MODULES` section label. No exclamation marks. No "Oops". Errors say what happened and what to do next. Subtitles under page titles are one line, plain English, describing the job — the existing "Customer invoicing, debtor management and receipts" is exactly right; keep that register everywhere.

Terminology is fixed: **customer** (not debtor, except in report names inherited from COBOL), **supplier** (not vendor or creditor), **post** (not submit or commit in UI text, even though the button is a commit internally), **financial year** (not fiscal year), **period** (not month), **enquiry** (not inquiry).

---

## 9. Accessibility and keyboard

- Text contrast ≥ 4.5:1; large text and icons ≥ 3:1. The token pairs above all clear this.
- Focus ring on every focusable element: 2px `--lm-focus` at 40% alpha, 2px offset. Never `outline: none` without a replacement. Removes the dotted default in the reporting suite.
- Minimum hit target 32px; 28px permitted only inside dense table rows where the whole row is also clickable.
- Reserved shortcuts, identical in both apps: `Ctrl+K` search · `Ctrl+S` save · `Ctrl+Enter` post · `Esc` cancel/close · `F2` edit row · `Alt+1..9` jump to module · `Ctrl+F` filter list.
- Every shortcut appears in the tooltip of the control it triggers. Discoverability is the point.
- Colour is never the sole carrier of meaning — status chips carry text, ageing columns carry headers.

---

## 10. Implementation plan for Claude Code

Branch per wave off `develop`, named `feat/ui-{slug}`. Complete each wave fully; do not pause for confirmation mid-wave.

### Wave 1 — `feat/ui-tokens` (both repos)
1. Add `landmark-tokens.css` to the reporting suite static CSS and import it first in the base layout.
2. Add `landmark-theme.css` to the desktop resources and apply it as the single stylesheet on the primary `Scene`.
3. Bundle IBM Plex Sans (400/500/600) and IBM Plex Mono (400/600) as woff2 in the reporting suite and ttf in the desktop `resources/fonts/`; load with `Font.loadFont` in the JavaFX startup path before the first scene is shown.
4. Create the `Module` enum in both repos from §4. Delete every other hard-coded nav list.
**Done when:** both apps launch with no visual change other than the new typeface, and no component CSS references a raw hex.

### Wave 2 — `feat/ui-shell` (both repos)
1. Header per §5.1 — brand left, context chip, right-aligned search and user menu. Remove the standalone "Switch Company"/"Switch company" buttons.
2. User menu popover per §7.2. Remove the red Sign Out button (D3) and the bare "NIGEL" text (R5).
3. Rail per §5.2 in both apps, driven by the `Module` enum, with Favourites above MODULES, the active-item spine, and a collapse toggle persisted per user.
4. Wire `tabler-pack` on the desktop and confirm every glyph in §4 resolves; fail the startup smoke test if any icon is missing (D1).
5. Page header component per §5.3.
**Done when:** screenshots of the same module in both apps have pixel-identical chrome above and left of the content region.

### Wave 3 — `feat/ui-primitives` (both repos)
Button, StatusChip, Badge, ModuleCard, ListRow, CommandBar, form controls. Delete the ad-hoc card and list styles both apps currently carry.
**Done when:** no component in either app defines its own padding, radius or border colour.

### Wave 4 — `feat/ui-reports-pages` (reporting suite)
1. Move the rail search into the CommandBar as a list filter (R2).
2. Rebuild report lists on `ListRow` with per-module accent tiles (R4) and the favourite star.
3. Rename "Business Activity Statement" to "BAS / Tax" (R1).
4. Report parameter panels onto the §7.11 form spec.
**Done when:** all six GL reports plus the FA and PY reports render on the shared primitives.

### Wave 5 — `feat/ui-desktop-pages` (desktop)
1. Add the three missing modules to the rail (D2).
2. Rebuild module landing pages on ModuleCard + ListRow, with "soon" items at normal contrast plus a badge (D4).
3. Remove the stray background band (D5).
4. Add the CommandBar to Pay Code Maintenance (PACD01) as the reference implementation.
**Done when:** PACD01 and the AR landing page match the spec, and are the templates every later screen is cut from.

### Wave 6 — `feat/ui-datatable` (both repos)
DataTable per §7.7 — density toggle, sticky header and totals, Plex Mono amount columns, drill-down contract, empty states. Then module dashboard cues per §7.10.
**Done when:** the AR ageing list and the GL trial balance both drill correctly and share the same table code path per platform.

### Wave 7 — `feat/ui-handoff`
Session handoff from desktop to reporting suite carrying user, company and financial year, opening on the matching module. With the shells identical, this should feel like one product.

---

## 11. Change log

| Date | Version | Change |
|---|---|---|
| 2026-07-25 | 1.0 | Initial system. Tokens, shell, components, module registry, seven implementation waves. |
