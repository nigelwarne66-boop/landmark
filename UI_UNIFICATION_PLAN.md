# Landmark — UI Unification Plan (Claude Code directions)

Goal: make the JavaFX desktop app and the Thymeleaf reporting suite read as **one ERP product** — shared chrome (title bar, header strip, nav rail), one design language, the pin logo top‑left next to "Landmark".

Two repos are involved. Run the desktop prompts in the desktop repo and the reporting prompts in the reporting repo. Phase 0 is authored once and **copied into both** repos so each Claude Code session has the same ground truth.

## Working conventions (carry into every prompt)
- Branch off `develop` using the existing scheme: `feat/ui-{slug}`, `refactor/ui-{slug}`, `docs/ui-{slug}`.
- Complete the full task scope without mid‑task confirmation prompts (per `CLAUDE.md`).
- `DESIGN_SYSTEM.md` is ground truth — read it first, never hardcode values that belong there.
- Verify before assuming: confirm exact file paths/class names in the repo and confirm any new dependency's Maven coordinates before adding them. Do not invent paths.
- Acceptance is visual: compare against the agreed mockup, and confirm no existing screen regresses.

---

## Canonical design tokens (the single source of truth for Phase 0)

These are the starting values. Phase 0 records them; later phases only reference them.

Surfaces / text / border (light theme):
- `bg-page` `#F6F7F9` · `bg-surface` `#FFFFFF` · `bg-selected` `#EEF3FB`
- `text-primary` `#1F2328` · `text-secondary` `#5B6168` · `text-tertiary` `#8A9099`
- `border` `#E3E5E8` · `border-strong` `#CFD2D6`
- `accent` `#185FA5` · `accent-bg` `#E6F1FB` · `accent-text` `#0C447C`

Type scale (two weights only — 400 regular, 500 medium):
- page H2 18/500 · page subtitle 13/400 secondary
- header company 14/500 · header FY 12/400 secondary
- nav item 13/400 (active 500) · card title 13/500 · card subtitle 12/400 tertiary · link rows 13/400 secondary

Shape: radius `md` 8px, `lg` 12px. Borders 0.5–1px.

Canonical module list **and order** (use everywhere, both repos):
1. General Ledger · `ti-chart-bar` · `#185FA5`
2. Accounts Receivable · `ti-users` · `#1D9E75`
3. Accounts Payable · `ti-file-invoice` · `#D85A30`
4. Cash Management · `ti-cash` · `#639922`
5. Purchasing · `ti-shopping-cart` · `#D4537E`
6. Inventory · `ti-package` · `#534AB7`
7. Fixed Assets · `ti-building-warehouse` · `#BA7517`
8. Payroll · `ti-id-badge` · `#0F6E56`
9. BAS / Tax · `ti-receipt-tax` · `#5F5E5A`
10. System · `ti-settings` · `#5F5E5A` (desktop only; reporting keeps its "Favourites" pseudo‑group at the top instead)

Naming decisions to enforce: "Inventory" (not "Inventory Management"), "BAS / Tax" (not "Business Activity Statement"), "Cash Management" present in the desktop nav.

---

## Phase 0 — Shared spec (author once, copy to both repos)

```
Branch: docs/ui-design-system

Create DESIGN_SYSTEM.md at the repo root capturing the single source of truth for the
unified UI. Include, verbatim from the values I provide:
  - the colour tokens (surfaces, text, border, accent)
  - the type scale and the two-weights rule
  - radius/border shape rules
  - the canonical 10-module list WITH fixed order, Tabler icon name, and accent hex per module
  - the naming decisions (Inventory / BAS / Tax / Cash Management)
Also document the three shared shell regions and their contract:
  1. Window title bar: pin logo + "Landmark" wordmark far left; window controls far right.
  2. App header strip: company name + FY (left), global search (right-of-centre),
     user menu = initials + name + chevron (far right, with company switch + sign out).
  3. Left nav rail: module list per the canon, icon + label, neutral selected background
     with the module accent as a 3px left bar.
This file is read-only ground truth for later UI work. Do not write any UI code in this prompt.
```

Paste the same `DESIGN_SYSTEM.md` into the other repo before starting its prompts.

---

## Phase 1 — Reporting suite (fastest path to the visual language)

### R1 — Tokens as CSS variables
```
Branch: refactor/ui-css-tokens

Read DESIGN_SYSTEM.md. Add a :root block of CSS custom properties for every token
(colours, radii, the module accent map). Put it in the global stylesheet loaded by the
base Thymeleaf layout. Refactor existing styles to consume the variables instead of
hardcoded colours/sizes. No visual change intended beyond consistency. Full scope, no prompts.
```

### R2 — Header lockup + nav reconciliation
```
Branch: feat/ui-shell-reporting

Read DESIGN_SYSTEM.md. In the base layout's top header, place the pin logo immediately
left of the "Landmark" wordmark (top-left). Keep company name + FY, search, and the
NIGEL user menu (company switch + sign out) in the same strip per the header contract.
Then update the left nav (templates/.../fragments or the sidebar fragment) so the module
list matches the canonical names AND order exactly, each with its Tabler icon and accent
left-bar on the active item. Keep the Favourites group at the top. Verify the actual
fragment/template paths before editing. Acceptance: header and nav match the mockup; all
existing report links still resolve.
```

(Report cards already match closely — no dedicated prompt unless audit finds drift.)

---

## Phase 2 — Desktop app (JavaFX)

### D1 — Tokens as JavaFX CSS
```
Branch: refactor/ui-fx-tokens

Read DESIGN_SYSTEM.md. Create a JavaFX stylesheet (e.g. resources/css/landmark.css) that
declares the tokens as looked-up colours on .root (-lm-accent, -lm-bg-page, -lm-bg-surface,
-lm-bg-selected, -lm-text-primary, -lm-text-secondary, -lm-text-tertiary, -lm-border, etc.).
Attach it to the primary Scene. Refactor existing inline -fx styling to reference the
looked-up colours where practical. No layout changes in this prompt.
```

### D2 — Pin in the title bar (the top-left logo request)
```
Branch: feat/ui-fx-window-icon

Goal: the pin shows top-left in the OS title bar and on the taskbar, next to "Landmark".
Export the existing pin SVG (LandmarkLogo source) to PNG at 16/32/48/64px and place under
resources/branding/. On the primary Stage, set stage.setTitle("Landmark") and
stage.getIcons().addAll(...) with those PNGs (smallest first). Verify the resource path
loads on a headless/packaged run. This is the minimal "pin next to Landmark" win.
```

### D3 — App header strip
```
Branch: feat/ui-fx-headerbar

Read DESIGN_SYSTEM.md. Build a reusable LandmarkHeaderBar (HBox) per the header contract:
company name + FY on the left (bound to AppSession), a search field right-of-centre, and a
user menu (MenuButton: initials avatar + "NIGEL" + chevron) with "Switch company" and
"Sign out" items wired to the existing session actions. Mount it directly under the title
bar in the main window layout, and REMOVE the company/FY/user block currently sitting
bottom-left. Style via landmark.css only. Acceptance: matches the mockup; switch-company
and sign-out behave exactly as before.
```

### D4 — Nav rail with icons + canon
```
Branch: feat/ui-fx-navrail

Read DESIGN_SYSTEM.md. Rebuild the left navigation as a styled rail: the 10 canonical
modules in the fixed order, each row = icon + label, selected row uses -lm-bg-selected with
a 3px left bar in the module accent. For icons, add Ikonli and a Tabler-equivalent icon
pack (VERIFY the exact Maven artifact id and the glyph enum names before use; map each
module to the icon named in DESIGN_SYSTEM.md). Preserve all existing navigation routing.
Acceptance: nav matches the mockup; every module still opens its current view.
```

### D5 — ModuleCard component + Payroll landing
```
Branch: feat/ui-fx-module-cards

Read DESIGN_SYSTEM.md. Create a reusable ModuleCard control (VBox: accent icon chip + title
+ subtitle + a column of link rows) styled via landmark.css to match the reporting cards.
Rebuild the Payroll landing (PayrollMenuController view) using three ModuleCards: Setup &
Maintenance, Pay Processing, Reports & Compliance. Give the Reports card an "Open" affordance
that will later launch the reporting view (leave a clearly-marked TODO hook for the action;
no cross-app wiring yet). Acceptance: Payroll landing matches the mockup; existing menu
actions still fire.
```

### D6 — (Optional, higher risk) Custom window chrome
```
Branch: feat/ui-fx-custom-titlebar

Read DESIGN_SYSTEM.md. Convert the primary Stage to StageStyle.UNDECORATED and build a
custom title bar (HBox .title-bar): LandmarkLogo node + "Landmark" label on the left, a
growing spacer, then minimise / maximise-restore / close buttons on the right. Implement
window dragging on the bar (mouse pressed/dragged -> stage X/Y) and wire the buttons
(setIconified, toggle maximised with bounds restore, close). Handle the known edge cases:
edge-resize (add resize handles or a resize helper), maximise on the correct screen for
multi-monitor, and a visible focus/blur state. Keep it behind a feature toggle so the
decorated window remains available if issues arise. Acceptance: window behaves like a
normal app window; pin + wordmark sit top-left in the custom bar.
```

---

## Phase 3 — Stitch the two products (later, architectural)

### S1 — Seamless reports launch
```
Branch: feat/ui-session-handoff

Goal: opening reports from the desktop app must not re-prompt for login. Both apps
authenticate against the same lmextract schema (MEUSERS/MEPASS) and resolve company + FY.
Design and implement a handoff: when the user opens a module's Reports card, pass the
authenticated identity + selected company + FY to the reporting app via a short-lived
single-use token (validated server-side against the shared schema), landing directly on
that module's report list. Document the token contract in DESIGN_SYSTEM.md. Do NOT pass
raw credentials. Acceptance: from the Payroll Reports card, the reporting suite opens on
the Payroll reports list already signed in, same company/FY.
```

---

## Suggested run order
Phase 0 → R1 → R2 → D1 → D2 → D3 → D4 → D5 → (D6 optional) → S1.

R1/R2 land the look in the web product quickly and validate the tokens before the heavier
JavaFX work. D2 satisfies the immediate "pin next to Landmark" request on day one.
