# Android design system — Servora colour system

> Source of truth for the visual language of the Servora field-service products
> (Web console + Android app). This document records the **design tokens** and
> how they map into Material 3 on Android (`ui/theme/Color.kt` +
> `ui/theme/Theme.kt`). The Web console consumes the same brand through the
> Material 3 theme in `apps/web/src/styles.scss` (generated `--mat-sys-*`
> tokens from the steel-blue brand ramps). The `Figma/` scaffold is a working
> export that is not committed to the repository.

## Principles applied

- Professional, calm, technical field-service software — not a generic
  enterprise app, not an AI-generated dashboard.
- **Steel blue is the brand. Black is the foundation. Neutral gray provides
  structure. Semantic colors communicate operational state.**
- Hierarchy: black/neutral-gray structure → steel-blue interaction → white/
  blue-gray content → green/amber/red operational status. No single colour
  dominates; no indigo, no neon blue, no excessive borders/shadows.
- Readable on a phone in sunlight; EN/FR bilingual.
- Dark mode is **designed**, not an inversion of light mode.
- Accessibility is calculated, not assumed: the appendix below records the
  computed WCAG contrast of every role pairing.

## Colour tokens

### Brand — steel blue (primary action, links, selection, focus)

| Token | Light | Dark | M3 role (Android) |
| --- | --- | --- | --- |
| `primary` | `#2B5EA7` | `#6B9BD9` | `primary` (buttons, focus, links) |
| `onPrimary` | `#FFFFFF` | `#001B3D` | `onPrimary` |
| `primaryContainer` | `#C5D9F5` | `#1E3A5F` | tinted avatar/icon chips, selected fills |
| `onPrimaryContainer` | `#0D2F62` | `#C5D9F5` | `onPrimaryContainer` |

### Secondary — quiet neutral fills (subordinate to primary)

| Token | Light | Dark | M3 role |
| --- | --- | --- | --- |
| `secondary` | `#F0F2F5` | `#1A1A1A` | quiet fills (segmented controls) |
| `onSecondary` | `#394460` | `#B8C4D8` | `onSecondary` |
| `secondaryContainer` | `#E0E4EC` | `#2A2A2A` | tinted surfaces, active nav |
| `onSecondaryContainer` | `#2E3A5A` | `#D0DAE8` | `onSecondaryContainer` |

### Tertiary — slate blue (slightly warmer; never purple/indigo)

| Token | Light | Dark | M3 role |
| --- | --- | --- | --- |
| `tertiary` | `#4A6080` | `#8FAFC8` | infrequent accents |
| `onTertiary` | `#FFFFFF` | `#001525` | `onTertiary` |
| `tertiaryContainer` | `#DAE5F2` | `#1E3044` | tinted surfaces |
| `onTertiaryContainer` | `#1A2E44` | `#B8CCE4` | `onTertiaryContainer` |

> **Text-hierarchy note.** The colour spec lists dark "tertiary text" hexes
> (`#9AACC4` / `#C0D0E4`) under its text-hierarchy section and different values
> under its tertiary colour-system section. The colour-system values win the M3
> `onTertiary`/`onTertiaryContainer` roles because they are the readable pair
> for the fills they sit on (`#001525` on `#8FAFC8` = 8.05:1; `#9AACC4` on
> that fill would be ~1.0:1). The hierarchy value is kept as the supporting
> text tier token (`SupportingTextDark` = `#9AACC4`) for dense metadata.

### Backgrounds & surfaces

Light mode keeps a cool near-white canvas; **dark mode is a true-black
foundation** (`#000000`) with neutral-gray elevation steps. Luminance, not blue
tint, carries the dark hierarchy:

| Token | Light | Dark | M3 role |
| --- | --- | --- | --- |
| `background` | `#F1F3F8` | `#000000` | `background` (app canvas) |
| `surface` | `#FFFFFF` | `#0A0A0A` | `surface` (inputs, cards) |
| `onBackground` / `onSurface` | `#131720` | `#E8EAF0` | primary text |
| `surfaceContainerLowest` | `#FFFFFF` | `#050505` | elevation tier |
| `surfaceContainerLow` | `#F7F8FA` | `#0F0F0F` | elevation tier |
| `surfaceContainer` | `#EEF0F5` | `#141414` | elevation tier |
| `surfaceContainerHigh` | `#E6E9EE` | `#1A1A1A` | elevation tier |
| `surfaceContainerHighest` | `#DFE3E9` | `#212121` | elevation tier |

True black is appropriate for OLED/AMOLED displays (black pixels can consume
less power than illuminated ones). No fixed battery-savings figure is claimed,
and true black is **not** claimed to improve outdoor visibility automatically —
outdoor readability must be validated on real devices in real conditions.

### Text hierarchy & variant text

| Tier | Light | Dark | M3 role |
| --- | --- | --- | --- |
| Primary text | `#131720` | `#E8EAF0` | `onSurface`/`onBackground` |
| Secondary text | `#52617A` | `#A8B4CC` | `onSurfaceVariant` |
| Supporting (tertiary) text | — | `#9AACC4` | extra token (`SupportingTextDark`) |
| Disabled text / fill | — | `#6B6B6B` / `#3D3D3D` | extra tokens (dark only) |
| `surfaceVariant` fill | `#F0F2F5` | `#1A1A1A` | `surfaceVariant` |

The light secondary text is `#52617A`, not the previous `#6E78A0`: the old
value measured 4.32:1 on white (below 4.5:1) and carried an indigo hue. Keep
secondary text comfortably readable (blue-gray, no excessive dimming).

### Lines & dividers — neutral gray

| Token | Light | Dark | M3 role |
| --- | --- | --- | --- |
| `outline` | `#D0D5DD` | `#3A3A3A` | field borders |
| `outlineVariant` | `#E4E7EC` | `#2A2A2A` | dividers |

### Semantic — error / success / warning

Independent of the brand: green/amber/red communicate operational state and are
never swapped for steel blue. Error roles map to the M3 `error` slots; the
success/warning tokens are declared in `Color.kt` (reserved for the
status-chip components as they land).

| State | Light role / on-role | Light container / on-container | Dark role / on-role | Dark container / on-container |
| --- | --- | --- | --- | --- |
| Error | `#C62828` / `#FFFFFF` | `#FFDAD6` / `#410002` | `#FF6B6B` / `#3D0000` | `#5C1A1A` / `#FFDAD6` |
| Success | `#2E7D32` / `#FFFFFF` | `#B7F0B1` / `#002204` | `#6BD98B` / `#003D1A` | `#1A4A2A` / `#B7F0B1` |
| Warning | `#B45309` / `#FFFFFF` | `#FFDCC0` / `#311300` | `#FFB86B` / `#3D2600` | `#5C3A1A` / `#FFDCC0` |

> The light warning fill is `#B45309`, not the draft `#F57C00`: white on
> `#F57C00` measures 2.70:1 (below the 4.5:1 target); white on `#B45309`
> measures 5.02:1 in the same amber direction (see appendix).

The full role mapping lives in `ui/theme/Color.kt` + `ui/theme/Theme.kt`.

## Accessibility validation appendix

Ratios below are **computed** from the WCAG 2.x relative-luminance formula
(no visual guesses). Targets: normal text ≥ 4.5:1, large text / UI components
≥ 3:1, small operational metadata ≥ 5.5:1 where practical.

### Light mode

| Pairing | Ratio |
| --- | --- |
| `onPrimary` on `primary` (`#FFFFFF` on `#2B5EA7`) | 6.44:1 |
| `onPrimaryContainer` on `primaryContainer` (`#0D2F62` on `#C5D9F5`) | 9.14:1 |
| `onSecondary` on `secondary` (`#394460` on `#F0F2F5`) | 8.63:1 |
| `onSecondaryContainer` on `secondaryContainer` (`#2E3A5A` on `#E0E4EC`) | 8.82:1 |
| `onTertiary` on `tertiary` (`#FFFFFF` on `#4A6080`) | 6.41:1 |
| `onTertiaryContainer` on `tertiaryContainer` (`#1A2E44` on `#DAE5F2`) | 10.85:1 |
| `onSurface` on `surface` (`#131720` on `#FFFFFF`) | 17.93:1 |
| `onBackground` on `background` (`#131720` on `#F1F3F8`) | 16.15:1 |
| `onSurfaceVariant` on `surface` (`#52617A` on `#FFFFFF`) | 6.27:1 |
| `onSurfaceVariant` on `background` (`#52617A` on `#F1F3F8`) | 5.65:1 |
| `onSurface` on `surfaceContainerHighest` | 13.92:1 |
| `onSurfaceVariant` on `surfaceContainerHighest` | 4.87:1 |
| Primary (links/icons) on `surface` (`#2B5EA7` on `#FFFFFF`) | 6.44:1 |
| Primary (links) on `background` (`#2B5EA7` on `#F1F3F8`) | 5.80:1 |
| Primary (selected icon) on `secondaryContainer` | 5.05:1 |
| Error text/icon on `surface` (`#C62828` on `#FFFFFF`) | 5.62:1 |
| `onError` on `error` (`#FFFFFF` on `#C62828`) | 5.62:1 |
| `onErrorContainer` on `errorContainer` | 13.26:1 |
| Success text/icon on `surface` (`#2E7D32` on `#FFFFFF`) | 5.13:1 |
| `onSuccess` on `success` | 5.13:1 |
| `onSuccessContainer` on `successContainer` | 13.09:1 |
| Warning text/icon on `surface` (`#B45309` on `#FFFFFF`) | 5.02:1 |
| `onWarning` on `warning` | 5.02:1 |
| `onWarningContainer` on `warningContainer` | 13.28:1 |

### Dark mode

| Pairing | Ratio |
| --- | --- |
| `onPrimary` on `primary` (`#001B3D` on `#6B9BD9` — primary button) | 5.98:1 |
| `onPrimaryContainer` on `primaryContainer` (`#C5D9F5` on `#1E3A5F`) | 8.01:1 |
| `onSecondary` on `secondary` (`#B8C4D8` on `#1A1A1A`) | 9.88:1 |
| `onSecondaryContainer` on `secondaryContainer` (`#D0DAE8` on `#2A2A2A`) | 10.17:1 |
| `onTertiary` on `tertiary` (`#001525` on `#8FAFC8`) | 8.05:1 |
| `onTertiaryContainer` on `tertiaryContainer` (`#B8CCE4` on `#1E3044`) | 8.19:1 |
| `onSurface` on `background` (`#E8EAF0` on `#000000`) | 17.46:1 |
| `onSurface` on `surface` (`#E8EAF0` on `#0A0A0A`) | 16.46:1 |
| `onSurface` on `surfaceContainerHighest` (`#E8EAF0` on `#212121`) | 13.39:1 |
| `onSurfaceVariant` on `surface` (`#A8B4CC` on `#0A0A0A`) | 9.49:1 |
| `onSurfaceVariant` on `background` (`#A8B4CC` on `#000000`) | 10.07:1 |
| `onSurfaceVariant` on `surfaceContainerHighest` | 7.72:1 |
| Supporting text on `surface` (`#9AACC4` on `#0A0A0A`) | 8.55:1 |
| Primary (links/icons) on `background` (`#6B9BD9` on `#000000`) | 7.32:1 |
| Primary (links) on `surface` (`#6B9BD9` on `#0A0A0A`) | 6.90:1 |
| Primary (selected icon) on `secondaryContainer` | 5.00:1 |
| Error text/icon on `surface` (`#FF6B6B` on `#0A0A0A`) | 7.13:1 |
| `onError` on `error` | 6.31:1 |
| `onErrorContainer` on `errorContainer` | 10.05:1 |
| Success text/icon on `surface` (`#6BD98B` on `#0A0A0A`) | 11.24:1 |
| `onSuccess` on `success` | 7.09:1 |
| `onSuccessContainer` on `successContainer` | 7.83:1 |
| Warning text/icon on `surface` (`#FFB86B` on `#0A0A0A`) | 11.62:1 |
| `onWarning` on `warning` | 8.35:1 |
| `onWarningContainer` on `warningContainer` | 7.83:1 |

Disabled text (`#6B6B6B` on `#0A0A0A` = 3.72:1) is intentionally below the text
threshold — disabled content is exempt from WCAG contrast requirements, and the
tokens are chosen so disabled controls remain clearly distinct from ordinary
secondary information (which reads at 7.72:1 or higher). Outlines/dividers are
decorative and deliberately subtle (light `#D0D5DD`, dark `#3A3A3A`); state and
focus never rely on them alone — focus uses the steel-blue primary ring.

## Typography

- Figma uses **DM Sans** for display/headings and **Inter** for body/labels
  (JetBrains Mono for time codes). Android currently uses the platform sans
  (Roboto) through the Material 3 type scale — no font binaries are bundled.
  Bundling DM Sans/Inter is an open decision (licensing/APK size).
- Hierarchy via the M3 type scale + weight: headings `SemiBold`, screen
  titles `headlineSmall`, labels `labelLarge`, meta `bodySmall`/`labelMedium`.

## Shape & spacing

- Radius: base 8 dp, controls (inputs, primary button) 12 dp, large
  surfaces/cards 16 dp → `Shapes(extraSmall 4, small 8, medium 12, large 16)`.
- Field height 52 dp, primary action height 52 dp, touch targets ≥ 48 dp.
- Content gutters 24 dp; form column max width 360 dp.

## Appearance controls (sign-in top bar)

The sign-in screen's top bar carries the two app-level appearance controls — the language pill and
the light/dark pill (`docs/decisions/007-android-appearance-controls.md`). They are built from the
tokens above, so they introduce no new colours:

| Part | Token |
| --- | --- |
| Track | `colorScheme.secondary`, `shapes.small` (8 dp) |
| Selected segment | `colorScheme.surface` on `colorScheme.onSurface`, `shapes.extraSmall` (4 dp), elevation 1 dp |
| Unselected segment | no fill, `colorScheme.onSurfaceVariant`, elevation 0 dp |

Metrics: track padding 4 dp, 2 dp between segments, segment height 40 dp (48 dp target with the
track), language segment min width 52 dp, theme segment width 44 dp, segment icon 18 dp. The
controls sit in the page gutters (20 dp) above the form, language leading and appearance trailing,
so the form keeps its own centred column below them.

Accessibility: the pair is a `selectableGroup` of `Role.RadioButton` segments. Each segment is
labelled by its own full name — "English" / "Français" for languages, "Light theme" / "Dark theme"
("Thème clair" / "Thème sombre") for appearance — and the visible two-letter language abbreviations
are marked `clearAndSetSemantics {}` so TalkBack does not spell them out. The selected segment
carries elevation as well as colour, so selection is never signalled by colour alone.

