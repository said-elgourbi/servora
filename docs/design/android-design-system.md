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

## Contextual top bar (signed-in screens)

The signed-in application draws one Material 3 `TopAppBar` (`ui/components/ServoraTopBar.kt`) instead
of a global brand header plus a separate per-screen header. A root destination shows the selected
bottom-navigation tab's title and no back control; a pushed screen shows its own title, a back control
and, where the user's permissions allow, one contextual action
(`docs/decisions/011-android-contextual-top-bar.md`).

| Part | Token |
| --- | --- |
| Container | `colorScheme.background` |
| Title | `colorScheme.onBackground`, `titleLarge`, bold, single line with ellipsis |
| Context line | `colorScheme.onSurfaceVariant`, `bodySmall`, single line with ellipsis; drawn only when the destination has one (Add Property names its customer) |
| Back control | `colorScheme.primary` glyph, `ic_chevron_left`, 22 dp inside a 48 dp `IconButton` |
| Contextual action | `TextButton` (for example View Customer's **Edit**), drawn only when permitted |

The bar leaves `TopAppBarDefaults.windowInsets` in place, so it applies the status-bar inset itself
and content begins directly below it. No status-bar height is hard-coded.

## Management action row (Job Details)
## Job Details — hierarchy and contextual actions

The Manager Job Details screen reads top to bottom as the manager's question: what the job is, the
visit that represents it, the technicians on that visit, and then the job's activity
(`docs/tracker/019-android-job-details-hierarchy.md`). Each action is drawn with the record it affects
rather than in one global action row, because a global row made every action look equally relevant to
the whole screen and separated each one from the data it changes (`BR-066`). An action is drawn only
when the session holds the capability the API enforces, and it is enabled from the backend's own answer
rather than from a rule re-implemented in the client (`BR-006`, `BR-007`, `BR-041`). The Job's status
control carries that rule one step further
(`docs/tracker/020-android-job-details-status-control.md`): the chip that presents the status **is** the
control that changes it, so the state a user wants to change is the thing they tap and no separate
action is drawn beside it.

Because a Job and its Visit are two state machines (`BR-059`), each status is named for what it belongs
to: the header's status is labelled **Job status**, and the Visit card labels its row for the Visit's
date rather than with a word that reads as a status, so the card's own badge is the only status a Visit
appears to have (`docs/tracker/021-android-job-details-status-polish.md`).

| Part | Token |
| --- | --- |
| Job identity | the Job number, `labelMedium` on `onSurfaceVariant`; then the title, `headlineSmall` bold on `onSurface`; then the description, `bodyMedium` on `onSurfaceVariant`. The number is stated once and is never repeated inside the title (`BR-052`). The three stay one group: the status is not drawn above them, so nothing separates the Job's number from what the Job is |
| Job status | the identity's own labelled control: a `SectionLabel` reading **Job status**, then the status control below it, so the status is read as the Job's rather than as the header's decoration or the Visit's (`BR-028`, `BR-059`) |
| Contextual action | `TextButton`, `labelLarge`, `heightIn(min = 48.dp)`; disabled with the platform's own dimming |
| Status control | a Material 3 clickable `Surface` shaped as the shared status chip (`JobStatusPill`): the current status's own container and content colours, its 1 dp `content@25%` border, an 8 dp dot in the chip's own content colour that marks it as a status, the chip's localized label, and a 16 dp `ic_chevron_down` in the same content colour saying it opens something. Tapping it opens the control's own compact menu — one fixed 216 dp column anchored at the chip — made of an `outlineVariant` divider under a non-acting row stating the status the Job is in now (its own dot, its localized label, `bodyMedium` semibold and a 16 dp `ic_check_circle` in its accent colour, with the "Current status" content description), then one `DropdownMenuItem` per transition the API permits (`BR-058`), each a `bodyMedium` label with a dot in that status's accent colour rather than a second row of chips. Without the capability, or with no permitted transition, the status is drawn as the same dot-led chip and does not act (`BR-006`, `BR-007`); the two states differ only by the affordance the control adds, because the clickable `Surface` keeps the chip's own size, reserves the platform's touch target around it and confines the ripple to the chip |
| Visit card row | the represented Visit's date is labelled **Visit date** (`labelMedium` on `onSurfaceVariant`, as every information row is) rather than with a word that reads as a status, and the Visit's status badge stays at the end of that row. The date row and the badge therefore state the Visit's date and its status, never the same thing twice (`BR-074`) |
| Visit card action | at the foot of the visit card behind an `outlineVariant` divider: **Reschedule**, disabled when the API says the visit is not reschedulable (`BR-073`) |
| Technicians section action | at the foot of the crew card behind the same divider: **Manage technicians**, which states the whole crew — adding a technician, removing one and naming the Lead (`BR-068`, `BR-069`) |
| Action report | a Material 3 `SnackbarHost` at the bottom of the screen. What an action did is transient: `secondaryContainer` on `onSecondaryContainer`, `SnackbarDuration.Short`. A refusal waits for the user: `errorContainer` on `onErrorContainer`, `SnackbarDuration.Indefinite`, with a **Dismiss** text button |
| Tappable information row | unchanged: no fill of its own; the row's whole width is the control, so the row's own tokens do not change. A row that opens the Job's address in the device's map application ends in the design's 18 dp `ic_navigation` glyph on `primary` (`Figma/src/screens/JobDetails.tsx`); the glyph also names that action for a screen reader, because the row's own text states the address and not what opening it does (`BR-028`). A Job with no address has nothing to navigate to, so that row is neither tappable nor marked (`BR-056`) |
| Assignment sheet | a Material 3 `ModalBottomSheet`: one row per technician with a `Checkbox` and a Lead choice, and one primary button; a crew that is not exactly one Lead cannot be confirmed |
| Reschedule dialog | an `AlertDialog` whose date and time fields open the platform pickers, plus length choices |
| Conflict confirmation | an `AlertDialog` listing each overlapping technician, job number and window, with **Schedule anyway** as its confirming action (`BR-070`) |

The action surfaces are localized in both languages and are the same rows the customer screens use for
their own lifecycle actions, so the two features do not drift apart.

**Why the report is transient.** A completed action needs no standing banner: the Job the API answered
with already presents the change, so the report says the same thing twice and holds space the record
needs (`BR-001`). A refusal is the opposite case — nothing on screen reflects a change that did not
happen — so it stays until the user dismisses it.

**Job Activity is a unified timeline** (`BR-080`, `docs/tracker/022-android-job-activity-timeline.md`).
Below the technicians, the section reads one chronological, newest-first column over the Job's own
events and every Visit's events. Each entry is a marker on a connecting vertical line — an initials
avatar for a note, a quiet dot for a system event — followed by the action as the primary text, and
`Visit N · member · time` (or `member · time` for a Job-level event) as secondary metadata. A Visit-level
entry states its Visit as the derived `Visit N` sequence, never a database id. The section keeps an
empty state, a loading state, and a failure state with retry, and the page reserves bottom clearance for
the floating Add update action so the last entry is never covered. Notes as a write source (and the Add
update action itself) are still undecided (`docs/tracker/022-android-job-activity-timeline.md`, open
questions).

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

The row also sits inside the status-bar safe area: the window draws under the system bars
(edge-to-edge is enforced from Android 15, and the Android app does not opt out), so the top bar is
offset by the inset the platform reports — `Modifier.windowInsetsPadding(WindowInsets.statusBars)`
— and the 20 dp gutters sit inside that inset. No status-bar height is hard-coded.

The system bar *content* follows the app's own appearance rather than the device setting: the
status-bar icons and the navigation-bar handle are painted dark for the light appearance and light
for the dark one (`ServoraTheme` → `ui/theme/SystemBarAppearance.kt`). A light app appearance on a
phone running dark mode therefore keeps readable dark system bar icons. Only the content appearance
is set; the window's edge-to-edge layout and the insets above are unchanged.

Accessibility: the pair is a `selectableGroup` of `Role.RadioButton` segments. Each segment is
labelled by its own full name — "English" / "Français" for languages, "Light theme" / "Dark theme"
("Thème clair" / "Thème sombre") for appearance — and the visible two-letter language abbreviations
are marked `clearAndSetSemantics {}` so TalkBack does not spell them out. The selected segment
carries elevation as well as colour, so selection is never signalled by colour alone.

## Keyboard (IME) inset (signed-in screens)

The signed-in shell applies the keyboard inset once, on the `Scaffold` itself
(`ServoraHomeScreen` → `Modifier.fillMaxSize().imePadding()`), so the keyboard shortens the whole
shell — top bar, destination content and bottom navigation — instead of covering its lower part.

Why the shell and not each form: applying the inset there shrinks the height every destination's
scrollable content is measured against. That is the resize Compose reacts to by bringing a focused
text field back into view, so the last field of a form (and the save/cancel row beneath it) stays
visible while it is typed in. Insetting only the form's own content would instead leave a
bottom-bar-sized gap between the form and the keyboard. Like the status-bar handling above, no
keyboard height is hard-coded: where the platform already resizes the window for the IME, the
reported inset is zero and nothing is applied twice.

## Collapsible section headings (disclosure)

A section whose own rows can grow long enough to push the rest of the screen out of sight puts its
heading behind a disclosure, so the user folds the section away rather than losing the information:
the heading stays, and it keeps the section's count. The manager home's **Needs attention** section
is the first adopter (`docs/tracker/016-android-manager-home.md`); the customer detail's
archived-Property disclosure uses the same chevron and rotation inside a section rather than as a
heading.

| Part | Token |
| --- | --- |
| Heading label | `colorScheme.onSurfaceVariant`, `labelSmall`, bold, from `section_count_format` (`"Label · n"`) — the same string the always-open sections' labels use |
| Chevron | `colorScheme.onSurfaceVariant`, `ic_chevron_right`, 18 dp, rotated 90° while the rows are shown |
| Touch target | the whole heading row, `≥ 48 dp` (`Modifier.heightIn(min = 48.dp)`) |
| Ripple | clipped to `shapes.medium`, so the feedback stays inside the row |
| Chevron content description | the action, not the state: "Show the items that need attention" / "Hide the items that need attention", localized (`BR-028`) |

Only the section's own rows are hidden. Its heading, its count and the sections around it stay
exactly where they were, and a section with nothing to show carries no chevron and no click.
Whether a section starts expanded or collapsed is presentation, and belongs to the screen that owns
the section (`BR-042`).

