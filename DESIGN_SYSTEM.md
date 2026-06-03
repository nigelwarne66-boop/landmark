# Landmark Design System

Single source of truth for the unified UI across the JavaFX desktop app and the Thymeleaf reporting suite. All phases read this file first and never hard-code values that belong here.

---

## Colour tokens

### Surfaces, text, border (light theme)

| Token | Hex | Usage |
|-------|-----|-------|
| `bg-page` | `#F6F7F9` | App background |
| `bg-surface` | `#FFFFFF` | Cards, panels |
| `bg-selected` | `#EEF3FB` | Selected nav item background |
| `text-primary` | `#1F2328` | Body copy, headings |
| `text-secondary` | `#5B6168` | Subtitles, descriptions |
| `text-tertiary` | `#8A9099` | Placeholders, inactive labels |
| `border` | `#E3E5E8` | Card borders, dividers |
| `border-strong` | `#CFD2D6` | Focused / hovered borders |
| `accent` | `#185FA5` | Active state bar, links, primary buttons |
| `accent-bg` | `#E6F1FB` | Active item tint, badge backgrounds |
| `accent-text` | `#0C447C` | Text on accent-bg surfaces |

### JavaFX mapping (landmark.css looked-up colours)

```
-lm-bg-page:      #F6F7F9;
-lm-bg-surface:   #FFFFFF;
-lm-bg-selected:  #EEF3FB;
-lm-text-primary:   #1F2328;
-lm-text-secondary: #5B6168;
-lm-text-tertiary:  #8A9099;
-lm-border:       #E3E5E8;
-lm-border-strong:#CFD2D6;
-lm-accent:       #185FA5;
-lm-accent-bg:    #E6F1FB;
-lm-accent-text:  #0C447C;
```

### Web mapping (CSS custom properties)

```css
--lm-bg-page:      #F6F7F9;
--lm-bg-surface:   #FFFFFF;
--lm-bg-selected:  #EEF3FB;
--lm-text-primary:   #1F2328;
--lm-text-secondary: #5B6168;
--lm-text-tertiary:  #8A9099;
--lm-border:       #E3E5E8;
--lm-border-strong:#CFD2D6;
--lm-accent:       #185FA5;
--lm-accent-bg:    #E6F1FB;
--lm-accent-text:  #0C447C;
```

---

## Type scale

Two weights only: **400 regular** and **500 medium**.

| Role | Size | Weight | Colour |
|------|------|--------|--------|
| Page heading (H2) | 18px | 500 | text-primary |
| Page subtitle | 13px | 400 | text-secondary |
| Header — company name | 14px | 500 | text-primary |
| Header — financial year | 12px | 400 | text-secondary |
| Nav item (inactive) | 13px | 400 | text-secondary |
| Nav item (active) | 13px | 500 | accent |
| Card title | 13px | 500 | text-primary |
| Card subtitle | 12px | 400 | text-tertiary |
| Card link rows | 13px | 400 | text-secondary |

---

## Shape & borders

| Token | Value |
|-------|-------|
| `radius-md` | 8px |
| `radius-lg` | 12px |
| Card border | 1px solid `border` |
| Dividers | 0.5px solid `border` |

---

## Canonical module list

Fixed order. Used in every navigation surface — desktop nav rail, web sidebar, and any module picker.

| # | Name | Tabler icon | Accent hex |
|---|------|-------------|------------|
| 1 | General Ledger | `ti-chart-bar` | `#185FA5` |
| 2 | Accounts Receivable | `ti-users` | `#1D9E75` |
| 3 | Accounts Payable | `ti-file-invoice` | `#D85A30` |
| 4 | Cash Management | `ti-cash` | `#639922` |
| 5 | Purchasing | `ti-shopping-cart` | `#D4537E` |
| 6 | Inventory | `ti-package` | `#534AB7` |
| 7 | Fixed Assets | `ti-building-warehouse` | `#BA7517` |
| 8 | Payroll | `ti-id-badge` | `#0F6E56` |
| 9 | BAS / Tax | `ti-receipt-tax` | `#5F5E5A` |
| 10 | System | `ti-settings` | `#5F5E5A` |

Notes:
- **System** appears in the desktop nav rail only. The reporting suite replaces it with a Favourites pseudo-group at the top.
- Icon names use the **Tabler Icons** set. Web: `<i class="ti ti-chart-bar">` via `@tabler/icons-webfont` CDN. JavaFX: `new FontIcon("tai-chart-bar")` via `ikonli-tabler-pack` when available in Maven Central — currently the desktop uses Feather equivalents (`fth-*`) from `ikonli-feather-pack:12.3.1`.
- Module accent colours are applied as a **3px left bar** on the active nav row (not as the row background).

### Naming decisions (enforce everywhere)

- "**Inventory**" — not "Inventory Management", not "Stock Management"
- "**BAS / Tax**" — not "Business Activity Statement"
- "**Cash Management**" — present in the desktop nav; not omitted
- "**Purchasing**" — not "Purchase Orders"
- "**Accounts Receivable**" — not "Debtors"
- "**Accounts Payable**" — not "Creditors"

---

## Three shared shell regions

Both apps implement the same three chrome regions. Styling differs (JavaFX vs Bootstrap/CSS) but the layout contract is identical.

### 1. Window title bar

- **Pin logo** (the Landmark pin-mark SVG, no wordmark) at far left.
- `"Landmark"` wordmark immediately right of the pin.
- Native OS window controls (minimise / maximise / close) at far right.
- JavaFX: set via `stage.setTitle("Landmark")` and `stage.getIcons()` with multi-size PNGs.

### 2. App header strip

A full-width bar mounted directly below the title bar, above all content.

| Zone | Content |
|------|---------|
| Left | **Company name** (14px/500) · pipe separator · **Financial year** (12px/400 secondary) |
| Centre-right | Global **search** field |
| Far right | User menu: **initials avatar** · **name** · chevron. Dropdown: Switch Company · Sign Out |

- Height: 48px.
- Background: `bg-surface` (#FFFFFF). Bottom border: 1px `border`.
- Company name and FY are bound to the active session (AppSession on desktop, ReportSession on web).
- The existing "switch company" and "sign out" actions wire into the user menu dropdown.

### 3. Left nav rail

- Width: 192px (desktop) / 240px (web, existing).
- Background: `bg-page` (#F6F7F9). Right border: 1px `border`.
- Top: "MODULES" section label (9px/700 uppercase, `text-tertiary`).
- Each row: **icon** (Tabler, 16px, module accent colour) + **label** (13px, `text-secondary` inactive / `text-primary`+500 active).
- Active row: background `bg-selected`, **3px left bar** in the **module accent colour**.
- No dark/navy sidebar — both apps use the light theme nav rail.

---

## Session handoff contract (Phase S1 — future)

When the desktop opens a module's Reports card, it will pass a short-lived single-use token to the reporting suite so the user is not re-prompted for login. Token contract to be documented here when Phase S1 is implemented.
