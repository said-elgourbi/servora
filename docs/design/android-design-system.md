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

## Job Details — the technician's hierarchy and contextual actions

One destination serves both audiences, and it reads top to bottom as the **technician's** questions
(`BR-010`, `BR-012`; `docs/tracker/052-android-technician-job-details-redesign.md`): what the work is (the
Job's number, title and description, as a heading rather than a card), where it is (**Location**), who can
be reached there (**Contacts**), what the office recorded (**Notes**), the Visit being worked (**Current
visit**, **Today's visit**, … with its working states and its field actions), and what has happened (the
Job's activity, grouped by the Visit each update belongs to). The Manager's own hierarchy — what the job
is, the visit that represents it, the technicians on that visit, then the activity
(`docs/tracker/019-android-job-details-hierarchy.md`) — is the same page, and each action is drawn with the
record it affects rather than in one global action row, because a global row made every action look equally
relevant to the whole screen and separated each one from the data it changes (`BR-066`). An action is drawn
only when the session holds the capability the API enforces, and it is enabled from the backend's own answer
rather than from a rule re-implemented in the client (`BR-006`, `BR-007`, `BR-041`).

**A routine Job status is not presented.** `ACTIVE` states only that the customer request is open, which a
technician acting on an assigned Visit already knows, so the page draws no status chip for a session that
cannot change it — and a Job that has *stopped* being open is reported by the prominent notice above the
work instead (`BR-058`, `BR-062`, `BR-079`). A session that **may** change the Job keeps the control the
Manager's path uses (`docs/tracker/020-android-job-details-status-control.md`): the chip that presents the
status **is** the control that changes it, so the state a user wants to change is the thing they tap and no
separate action is drawn beside it.

Because a Job and its Visit are two state machines (`BR-059`), each status is named for what it belongs
to: the Job's control is labelled **Job status**, and the Visit card labels its row for the Visit's date
rather than with a word that reads as a status, so the card's own badge is the only status a Visit appears
to have (`docs/tracker/021-android-job-details-status-polish.md`). Neither is ever drawn beside the other.

The section that presents the represented Visit is labelled for **when that field attempt is for**, as
of the device's own clock and zone (`docs/tracker/046-android-job-details-visit-period-and-card.md`): the
Visit the read selected (`BR-081`) is described as the **current** one only while its field work is under
way or its scheduled window is running (`BR-072`, `BR-074`), and otherwise as **Today's visit**,
**Tomorrow's visit**, an **Upcoming visit** or a **Previous visit**. A Visit with no schedule this build
can read keeps the current label, because the read selected it and there is no time to describe it
against (`BR-042`, `BR-051`). The label states a period and never a lifecycle: no status, schedule or
selection is decided by it (`BR-001`, `BR-007`).

| Part | Token |
| --- | --- |
| Job identity | the Job number, `labelMedium` on `onSurfaceVariant`; then the title, `headlineSmall` bold on `onSurface`; then the description, `bodyMedium` on `onSurfaceVariant`. The number is stated once and is never repeated inside the title (`BR-052`). The three are a heading rather than a card — the page's hierarchy, not one record among the record cards — and they stay one group, so nothing separates the Job's number from what the Job is (`BR-012`) |
| Job status (only for a session that may change it) | a compact labelled control inside the Job heading group: a `labelSmall` **Job status** label over the same status chip/menu, so status reads as part of the Job summary rather than as a large standalone section. A session that cannot act on the Job is drawn no status at all, and a closed Job is drawn as the notice below instead (`BR-012`, `BR-028`, `BR-059`, `BR-062`) |
| Location | a `SectionLabel` reading **Location** over one `InfoCard`: the Job's preserved address snapshot (`BR-056`) as an `OverviewRow` labelled **Address**, ending in the 18 dp `ic_navigation` affordance (`primary`) that opens it in the device's map application, with the row itself the target. A Job with no address states **No address yet** and is not tappable (`BR-049`, `BR-012`) |
| Contacts | a `SectionLabel` reading **Contacts** over one `InfoCard` — see *Job Details — the Contacts section* below (`BR-092`, `BR-095`) |
| Notes | a `SectionLabel` reading **Notes** over one `InfoCard` holding the notes the office recorded (`BR-092`), in `bodyLarge`, drawn only when the Customer has notes: they are what a technician needs to know before working, so they are not a row of the contact metadata (`BR-012`) |
| Contextual action | `TextButton`, `labelLarge`, `heightIn(min = 48.dp)`; disabled with the platform's own dimming |
| Status control | a Material 3 clickable `Surface` shaped as the shared status chip (`JobStatusPill`): the current status's own container and content colours, its 1 dp `content@25%` border, an 8 dp dot in the chip's own content colour that marks it as a status, the chip's localized label, and a 16 dp `ic_chevron_down` in the same content colour saying it opens something. Tapping it opens the control's own compact menu — one fixed 216 dp column anchored at the chip — made of an `outlineVariant` divider under a non-acting row stating the status the Job is in now (its own dot, its localized label, `bodyMedium` semibold and a 16 dp `ic_check_circle` in its accent colour, with the "Current status" content description), then one `DropdownMenuItem` per destination the API reported (`BR-058`), each a `bodyMedium` label with a dot in that status's accent colour rather than a second row of chips. Every destination the API reported is listed, in the status vocabulary's order: an open Job offers each other open status and `Completed`, and a closed Job offers only `New`; a destination the Job does not currently qualify for is still listed, because the API answers that question (`BR-061`, `BR-062`). Choosing a destination sends **one** change; the two consequential destinations are confirmed first by a Material 3 `AlertDialog` at the screen's dialog width — **Close this job?** (with a body naming the Job, and confirming that closing ends the field work and makes the visit outcomes final) or **Reopen this job?** — whose actions are a filled **Confirm** and a text **Cancel** (`BR-062`, `BR-063`). Without the capability, or with no permitted destination, the status is drawn as the same dot-led chip and does not act (`BR-006`, `BR-007`); the two states differ only by the affordance the control adds, because the clickable `Surface` keeps the chip's own size, reserves the platform's touch target around it and confines the ripple to the chip |
| Visit card row | the represented Visit's date is labelled **Visit date** (`labelMedium` on `onSurfaceVariant`, as every information row is) rather than with a word that reads as a status, and the Visit's status badge stays at the end of that row. The date row and the badge therefore state the Visit's date and its status, never the same thing twice (`BR-074`) |
| Visit working-status bar | one `SingleChoiceSegmentedButtonRow` across the Visit card's width, under an `outlineVariant` divider, and **only** for a session that may drive the Visit: four equal segments — **Scheduled**, **En route**, **On site**, **In progress** — at `labelSmall` bold, centred, the Visit's own state filled `primaryContainer` on `onPrimaryContainer` and the others Material's muted unselected colours, sharing one outline so the four read as **one bar** rather than as four objects (`docs/tracker/054-android-job-details-visit-status-bar.md`). Four equal segments rather than four chips that size to their own labels, because a ragged chip row wraps as soon as a label grows — in French, or at a larger text size — and one control then reads as two unrelated rows (`BR-012`, `BR-028`). The state the Visit holds is the selected segment and is **not** tappable — moving a Visit to where it already is is not a transition — so the disabled-and-selected colours are stated as the selected ones and the current state stays the obvious one; a segment that is neither the Visit's state nor a destination the API reported is muted. The row carries **no group label**: each segment names the state it moves the Visit to, `Role.RadioButton` inside the row's `selectableGroup` announces the selected one, and the section's own label above already says when the Visit is for, which is why the visible **Visit status** label was removed. A session that may not drive the Visit is shown the Visit's badge on the date row instead (`BR-006`, `BR-007`, `BR-041`, `BR-074`) |
| Visit section label | the section's `SectionLabel`, stating **when the represented Visit is for**: `Current visit`, `Today's visit`, `Tomorrow's visit`, `Upcoming visit` or `Previous visit`, resolved from the Visit's own schedule, its field status and the device's clock (`BR-072`, `BR-074`, `BR-081`) |
| Visit card action | at the foot of the visit card behind an `outlineVariant` divider: **Reschedule**, disabled when the API says the visit is not reschedulable (`BR-073`), and **Manage technicians** when the crew may be edited. **Schedule a visit** appears only when the Job has no Visit yet; **Schedule follow-up visit** appears only after the represented Visit is completed with a follow-up outcome. A scheduled or active Visit does not offer casual creation of a parallel Visit. **Request another visit** remains the technician's follow-up request action (`BR-071`, `BR-078`, `BR-FV-001`) |
| Technicians section action | at the foot of the crew card behind the same divider: **Manage technicians**, which states the whole crew — adding a technician, removing one and naming the Lead (`BR-068`, `BR-069`) |
| Action report | a Material 3 `SnackbarHost` at the bottom of the screen, drawn **above whatever the screen pins below it** — the floating **Add update** action, and the audio notice / photo tray while the device holds evidence the API has not accepted — so a report is never covered by one of them and its whole text is readable (`BR-042`; reported from physical-device QA and fixed 2026-09-17). What an action did is transient: `secondaryContainer` on `onSecondaryContainer`, `SnackbarDuration.Short`. A refusal waits for the user: `errorContainer` on `onErrorContainer`, `SnackbarDuration.Indefinite`, with a **Dismiss** text button |
| Tappable information row | unchanged: no fill of its own; the row's whole width is the control, so the row's own tokens do not change. A row that opens the Job's address in the device's map application ends in the design's 18 dp `ic_navigation` glyph on `primary` (`Figma/src/screens/JobDetails.tsx`); the glyph also names that action for a screen reader, because the row's own text states the address and not what opening it does (`BR-028`). A Job with no address has nothing to navigate to, so that row is neither tappable nor marked (`BR-056`) |
| Assignment sheet | a Material 3 `ModalBottomSheet`: one row per technician with a `Checkbox` and a Lead choice, and one primary button; a crew that is not exactly one Lead cannot be confirmed |
| Visit window sheet | one Material 3 `ModalBottomSheet` shared by the three writes that state a Visit's window — **Schedule a visit** and **Approve & schedule** (`BR-071`, `BR-FV-005`) and the technician's **Request another visit** (`BR-FV-001`): **one** visit date with a start and an end time, because a field attempt starts and ends on the same day (`BR-072`), whose date and time rows open the platform pickers and whose values are read back in the device's own zone and conventions (`BR-028`). The two office writes also state the crew, since a Visit that becomes `SCHEDULED` carries exactly one Lead (`BR-068`); the request states a **reason**, whether the same technician is preferred, and **no crew**, because who performs the follow-up is the office's decision (`BR-FV-003`, `BR-FV-004`). What the API would refuse cannot be submitted — an end that is not after the start, a crew that is not exactly one Lead, a request with no reason — and the form says which is missing instead (`BR-042`) |
| Follow-up request sheet | the window sheet's request mode, drawn as a proposal rather than an appointment: why another attempt is needed, the suggested window, the same-technician choice, and a message stating that nothing is booked until the office approves it (`BR-FV-002`, `BR-FV-010`). Submitting reports that the request reached the office; it never reports a Visit as scheduled |
| Reschedule dialog | an `AlertDialog` whose date and time fields open the platform pickers, plus length choices |
| Conflict confirmation | an `AlertDialog` listing each overlapping technician, job number and window, with **Schedule anyway** as its confirming action (`BR-070`) |
| Job update action and sheet | one floating **Add update** action at the bottom of the screen — an `ExtendedFloatingActionButton` in `primary` on `onPrimary` with the 18 dp `ic_add` — opening a Material 3 `ModalBottomSheet` titled *Add update*. The sheet is a **hierarchy**: it states the **kind** of update, then draws only that kind's own controls (`docs/tracker/033-android-add-update-hierarchy.md`). The kinds are peers in one segmented control (`JobUpdateKindSelector`) under the `labelSmall` group label **Update type**: one equal-width segment per kind the session may add — **Write note** (`ic_file_text`), **Add photo** (`ic_camera`) and **Add audio** (`ic_mic`, lucide `Mic`) — each drawn as its glyph over its `labelMedium` label, the selected one filled `primary`/`onPrimary` and the others `secondary`/`onSurfaceVariant` with a 1 dp `outlineVariant` hairline. It is a `selectableGroup` of `Role.RadioButton` segments, and the segments share the tallest label's height (`height(IntrinsicSize.Min)`), so three peers fit a phone in either language and grow with a larger text size instead of being squeezed into one 56 dp row (`BR-028`, `ADR-007` D6). Each kind then owns the controls below the selector. **Write note** draws the note's `OutlinedTextField` (4 lines, `outline` label and placeholder) with a `TextButton` **Cancel** and a `Button` **Save update** in `shapes.medium`, exactly as the photo review panel's foot is drawn; it is the kind in effect where a note can be taken, so its field is ready to type in and tapping the segment puts the cursor in it. **Add photo** draws a `labelSmall` label **Add photos from** and, under it, its two sources as **subordinate rows** — `≥ 56 dp` with the whole row as the target, a 22 dp leading glyph in `primary`, the label in `bodyLarge`, an 18 dp `ic_chevron_right` in `onSurfaceVariant`, and no fill, border or card, so they never read as further kinds: **Take photo** (`ic_camera`) and **Choose from device** (`ic_photo_library`, lucide `Images`). Each source closes the sheet and hands over to the flow the photo slice owns (capture or pick, then the review panel, then the tray). **Add audio** draws the recorder's own controls (`BR-091`, `ADR-018`, `docs/tracker/035-android-audio-evidence.md`): the phase selector — the vocabulary photos and audio both carry (`ADR-018` A4) — and then exactly one of three states: the **live-microphone** row (`ic_mic` in `primary` beside *Recording…* in `bodyLarge`) with a 56 dp `Button` **Stop**; the **no take yet** state, a 56 dp `Button` **Record** (`ic_mic`) with a `labelSmall` hint that the take is reviewed before it is attached, which is also the one control that asks for the microphone permission; or the **review** of the take already on the device — its length (`0:18`) beside `ic_mic`, the note's `OutlinedTextField`, and the foot `TextButton` **Delete** / `Button` **Attach to update**. Deleting is what makes another take possible, so *delete/re-record* is one step back to the first state rather than a second recorder beside the first; playing a take back is that tracker's Phase 9c, where one player serves the review and the timeline together. A kind keeps its own draft while the sheet is open (`rememberSaveableStateHolder`), so looking at the photos does not discard a half-typed note. The kinds are drawn on the capability the API enforces for each — the note on the Job update capability **and** a represented Visit, the photo sources on `evidence.photo.add`, audio on `evidence.audio.add` — so a default Technician reaches the camera, the picker and the microphone without being given a Manager capability (`BR-006`, `BR-007`, `BR-009`, `BR-011`, `ADR-018` A7), and the action itself is drawn when the session may do any of them (`BR-015`, `BR-051`); the tray or the notice takes the bottom of the screen while the device holds evidence the API has not accepted, as the tray already did. **Answered (`D14` ✓, 2026-09-16):** the sheet is the canonical way to choose the evidence kind, while inside the photo flow the tray's **Take another** launches the camera again rather than returning the technician to the picker — a run of photos stays one tap each, and the sheet is what changes the kind (`docs/tracker/029-photo-evidence-phases.md`) |
| Photo review panel | the `ModalBottomSheet` a capture — and each item of a pick — is confirmed in (`BR-012`, `BR-014`, `docs/tracker/029-photo-evidence-phases.md` Phase 3): the photo, the phase label with its three 56 dp targets, one optional note (2 lines at least, 3 shown), and a **Discard** / **Add** foot of the phase buttons' height, with the phase already at the value the technician used last so a run of photos of the same phase is one tap each. The photo is drawn `ContentScale.Fit` — the whole photo rather than a crop of it, because what is being confirmed is the photograph — in a 220 dp box, and the panel's **content scrolls** with its **Discard**/**Add** foot pinned beneath the scrolling area, so the note stays reachable while the keyboard is up and the two decisions stay on screen with it (the panel's scroll fixed 2026-09-16, its pinned foot 2026-09-28 with the keyboard rule below; the tray and gallery tiles still crop, being squares that stand for a photo) |
| Photo viewer | a full-screen `Dialog` (`usePlatformDefaultWidth = false`) drawn on the viewer's own **black ground with white ink** — a dedicated media surface, the same in both app appearances rather than a card of the light or dark theme, so the photo is read as the photo (`docs/tracker/031-android-photo-viewer-ui.md`). The photo is the content, drawn `ContentScale.Fit`, filling everything between the system bars and the note; the chrome is an overlay on it and takes no height of its own. It opens from a tap on a gallery tile or on a tray tile, and closes on its own action or the platform's back gesture. Zoom, swiping between photos and a gallery-wide pager are deliberately not drawn (`D4`, `docs/tracker/029-photo-evidence-phases.md` Phase 4). **Since Phase 4d (2026-09-15, `D9` ✓) the photo is drawn zoomable and pannable**: it is drawn through **Telephoto** over the Coil 3 image stack Phase 4c put behind `JobPhotoImages` — a pinch to zoom, a drag to pan within the photo's own bounds, a double-tap to the library's zoom ceiling, and no scale below "fit" — and what a zoom shows is the photo's own pixels, because the layer sub-samples the file the stack cached. The gesture surface is the area the photo occupies, so the phase badge, the top bar and the note stay legible and outside the gestures, and the platform back gesture keeps closing the viewer. **Since Phase 4e (2026-09-15, `D11`/`D12`/`D13` ✓) the viewer pages, saves and shares.** The Job's photos are drawn as one `HorizontalPager` in the order Job Details presents them — the evidence the backend holds first, as the gallery lists it, then the photos this device still holds, as the tray lists them — so a tap on a tray tile and a tap on a gallery tile open the same sequence, and a swipe continues through what the technician sees around the photo they opened. The top row carries the phase badge, a localized position (`1 / 4`, drawn only when the Job has more than one photo) and the same 48 dp close target; the note stays under the photo and pages with it, bounded and scrollable so a long one cannot push the photo out; and a photo the backend has not accepted yet keeps reporting its upload state beneath it. A swipe pages **only while the photo is at fit** — zoomed, the same drag pans the photo (`D9`) — so the two gestures never compete for one drag. **Since Phase 4f (2026-09-16, `docs/tracker/031-android-photo-viewer-ui.md`) the chrome is one minimal overlay:** a top bar — a flat `black@62%` scrim, so the controls' contrast comes from the viewer rather than from the photo under them — carries a 48 dp close target (`ic_close`) on the left, the localized position (`1 / 4`, only when the Job has more than one photo, `titleSmall` in white) centred in the screen, and the evidence actions as 48 dp icons on the right; the photo's phase is the same badge the tile draws, over the photo's own bottom-left corner; and under the photo a **Notes** section states the note with an explicit **More**/**Less** once it passes three lines, so a long note never takes the photo's room and a short one draws no action at all. An action that is running shows the viewer's own progress in its place, and what one did is reported by the same Snackbar the screen draws, hosted **inside the viewer** — the screen's own host sits behind the full-screen dialog, so a report drawn there is one the technician never sees. The two evidence actions sit in the top bar at 48 dp: **Save to device** (`ic_download`) and **Share** (`ic_share`, the platform's own chooser), each carrying its own localized label as its accessible name (`BR-028`). Both read the photo's bytes and are therefore drawn on `evidence.view`, the capability the API enforces for reading evidence; neither edits nor deletes anything (`BR-027`). **Since Phase 6b (2026-09-16, `BR-089`) a third action sits beside them for a session that holds `evidence.photo.remove`**: **Remove evidence** (`ic_trash`, lucide `Trash2`) is drawn **last**, so it is the outermost control and is never under the technician's thumb on the way to Save or Share, and it is disabled while any evidence action is running. It does not act directly: it opens a **confirmation dialog** — title *Remove this photo?*, a sentence stating that the photo was recorded as evidence and cannot be deleted, its own reason field (*Why is it being removed?*) and **Cancel** / **Remove** actions — and the Remove action is disabled until a reason is given, because the API refuses a removal without one (`BR-067`, `BR-089`). The action reports that it is working while the API answers, and a refusal, a photo that is no longer available, an unreachable server and a missing capability are each reported as themselves (`BR-042`). A technician's viewer draws no such control: removing recorded evidence is a Manager capability. **A gallery-wide pager and any cross-Job gallery remain deliberately not drawn** (`D4`, `D9`, `D11`) |

The action surfaces are localized in both languages and are the same rows the customer screens use for
their own lifecycle actions, so the two features do not drift apart.

**Why the report is transient.** A completed action needs no standing banner: the Job the API answered
with already presents the change, so the report says the same thing twice and holds space the record
needs (`BR-001`). A refusal is the opposite case — nothing on screen reflects a change that did not
happen — so it stays until the user dismisses it.

**Job Activity is grouped by the Visit each update belongs to** (`BR-047`, `BR-080`,
`docs/tracker/045-android-job-activity-visit-groups.md`, reworked by
`docs/tracker/052-android-technician-job-details-redesign.md`). The section holds one foldable group per
Visit — headed `Visit N · date` over a summary line stating where that field attempt ended up and what it
resulted in (`Completed · Needs follow-up`, from the shared status and outcome labels, `BR-074`, `BR-078`) —
and a group of its own for the events that belong to the Job and to no Visit (**General job updates**). The
Visit the read represents opens by default; every other Visit's group starts folded. The general group
starts **folded**: after `docs/tracker/056-evidence-belongs-to-a-visit.md` it holds only the office's
Job-level lifecycle events (`Job status changed`, `Property changed`, `Customer changed`, `BR-080`) and any
evidence recorded before that change, because everything a technician records in the field — a text note, a
photo, a recording — belongs to a Visit (`BR-015`, `BR-091`). It stays one tap away rather than being removed,
because those Job-level events are history no other surface states. What an expanded group reveals is that
Visit's own accepted evidence, read as a compact gallery, and then its primary operational entries as the one timeline they are; administrative/system entries — assignment changes, schedule creation, routine status bookkeeping and other low-value audit history — remain available in a collapsed **Administrative history** disclosure inside the same group rather than dominating the timeline;
it does **not** repeat the Visit's date, its scheduled window, its outcome or its crew, which the group's own
heading and the Visit section already state (`BR-047`, `BR-068`, `BR-072`, `BR-077`). A Visit with nothing
recorded says so inside its own group (`BR-042`). Which group an entry belongs to is the API's own
`visitSequence` answer, and an entry naming no Visit of the Job is reported as the Job's rather than guessed
into one.

**A Visit status event is stated in the language of the field** (`BR-080`, `BR-012`,
`docs/tracker/052-android-technician-job-details-redesign.md`). `VISIT_STATUS_CHANGED` reads `Work started`,
`Arrived on site`, `En route`, `Visit scheduled`, `Visit completed` or `Visit canceled` — one field phrase
per destination the status route offers, in place of `Updated visit status to IN_PROGRESS` — and
`VISIT_OUTCOME_RECORDED` states the outcome itself (`Needs follow-up`, `Resolved`), so a completion and what
resulted from it read as one account. A code this build cannot name reports itself generically rather than
being given another status's wording, and no audit record is altered by the wording: the event, both its
statuses, its actor and its time are the API's own (`BR-001`, `BR-041`, `BR-042`, `BR-067`).

**Inside a group, the entries are the one timeline** (`BR-080`,
`docs/tracker/022-android-job-activity-timeline.md`). A group's entries are a slice of the Job's one
chronological, newest-first read, never a re-sorted copy of it. Each entry is a marker on a connecting
vertical line — an initials avatar for a note, a quiet dot for a system event — followed by the action as
the primary text, and `Visit N · member · time` (or `member · time` for a Job-level event) as secondary
metadata. A Visit-level entry states its Visit as the derived `Visit N` sequence, never a database id. The
section keeps a loading state and a failure state with retry, emptiness is stated by the group that is
empty, and the page reserves bottom clearance for the floating Add update action so the last entry is never
covered.

**The rail is secondary, and narrow** (`BR-012`, `docs/tracker/032-android-job-activity-photo-ux.md`).
The marker is 28 dp, the rail is 28 dp wide with an 8 dp gap, and the connecting line stays
`outlineVariant`: the line organizes the entries and must not compete with what it introduces or cost the
content beside it width. The column that carries an entry's title, metadata and evidence therefore keeps
36 dp less indent than it did before this refinement.

**A photo entry draws the photo** (`BR-015`, `BR-027`, `BR-080`,
`docs/tracker/032-android-job-activity-photo-ux.md`). A `JOB_PHOTO_ADDED` entry reads **Added a photo**
and then shows the evidence itself: a 176 × 132 dp thumbnail (`ContentScale.Crop`, so it is a summary of
the photo rather than a distorted one), the same `JobPhotoPhaseBadge` the gallery tile, the tray tile and
the viewer draw over its bottom-left corner, and the technician's note underneath it — with the rest of a
long note behind a **More** action, so one entry cannot take the screen. The title does **not** repeat
the phase, because the badge states it (`BR-028`). The photo is a control whose whole area is the target
and whose accessible name is the action (`View photo`); a photo with no note draws no caption area, and a
photo whose id this build cannot read is left out rather than replaced by another picture (`BR-042`).

**One Add update action adds everything to the Activity** (`BR-012`, `BR-027`,
`docs/tracker/028-android-unified-job-update.md`). The section itself draws no action — a second
**Add photo** action used to sit beside it, and two entry points for one write made the technician choose
a place before choosing what they were recording. The action opens the sheet that states the kind, and the
accepted photos it produces are drawn in the gallery above the timeline.

**Where that one action is drawn** (`BR-012`, `BR-027`,
`docs/tracker/053-android-job-details-add-update-floating-action.md`). The action is the page's own
**floating** action — an `ExtendedFloatingActionButton` in `primary` on `onPrimary` with the 18 dp
`ic_add`, pinned to the bottom end with a 16 dp inset — and it is drawn at **every** scroll position
rather than only once part of the record has been read past: a technician's update is the write this page
exists for, so it is reachable the moment the Job is read. It is therefore the **one** Add update control,
and the Current Visit card draws no second one, so two entry points for one write never make the technician
choose a place before choosing what they are recording. **Complete visit** stays where the field attempt
is — the card's own action, a `FilledTonalButton` in `shapes.medium` with its 22 dp `ic_check_circle`
across the card's width — because completing a Visit is an action **on** that Visit rather than an entry
point into the Activity. The floating action is withheld while the device holds evidence the API has not
accepted, because the bottom of the screen belongs to the tray or the notice (`BR-014`), and the action
itself is drawn only when the API's `addUpdateAllowed` on the represented Visit and the session's own
capabilities both allow a write (`BR-006`, `BR-007`, `BR-062`, `BR-079`).

**The gallery is a collapsible section** (`BR-012`, `BR-080`,
`docs/tracker/032-android-job-activity-photo-ux.md`). **Photos · n** — the count from the shared
`section_count_format` string, in `labelSmall` bold on `onSurfaceVariant` — is the section's heading and
its control: the whole row is the target (`≥ 48 dp`, `shapes.medium` ripple) and the `ic_chevron_right`
turns a quarter turn while the strip is shown, exactly as the disclosures in the
**Collapsible section headings** section below. It carries `Show photos` / `Hide photos` as its
description, so a screen reader hears the action rather than the state (`BR-028`). It starts **folded**,
because the timeline draws each photo in the entry that recorded it; while folded the heading previews up
to three of the photos it counts at 32 dp (decorative, no content description of their own, because the
label and the count already say what they are). Unfolded, it draws the horizontal strip of 132 dp tiles —
phase badge and note snippet — that each open the same viewer.

**A photo tile is a control, and it opens the photo** (`D4`, `docs/tracker/029-photo-evidence-phases.md`
Phase 4). Each tile in the gallery — and each tile in the tray — is a tap target whose whole column
carries the tap, so the target is larger than the picture and the note under it opens the photo too
(`BR-012`). The tap opens the **viewer** row above: the photo itself rather than the tile's 512 px
preview, with the phase it was recorded in and the technician's whole note. The action is named for a
screen reader by a localized `onClickLabel`, because the photo's own content description says what it is
and not what tapping it does (`BR-028`). A photo that cannot be read is reported in the viewer instead of
being replaced by another picture (`BR-042`).

**A refused tray tile is the one tile with an action of its own** (`D6c`,
`docs/tracker/029-photo-evidence-phases.md` Phase 6a). While a tile reports an upload state, its own
controls are absent — the X belongs to a photo that has not been submitted, and it is not drawn once the
upload is queued (`BR-014`) — **except** when the state is **Refused by the server**: that upload is
finished and can never be replayed, so the tile adds a **Discard** `TextButton` under the reason it
reports, and that button is the tile's only control (no X beside it). It is a plain `TextButton` — a
`labelMedium` label on the tile's width, the `primary` ink every other tray action uses, with Material's
own 48 dp touch target rather than a second filled button competing with **Save photos** — and it performs
the same local removal the X performs before submission, because the two are one action on the device's
own copy of a photo the backend does not hold (`BR-041`). A queued or retrying upload draws neither
control: the API may still accept it.

**A recording is played back, and a recording the API accepted is removed, from the Job's Activity**
(`BR-091`, `BR-080`, `BR-089`, `ADR-018` A9/A11, `docs/tracker/035-android-audio-evidence.md` Phases 9c/9e).
A photo is drawn as the photo itself; a recording cannot be, so the `JOB_AUDIO_ADDED` entry carries what
stands for it: a full-width `secondary` container in `shapes.medium` drawn as **two lines** — on the first,
left to right, the **play/pause control**, the recording's **position** (`0:04 / 0:18`), its phase badge
and, for a session holding `evidence.audio.remove`, the removal control; on the second, the full-width
**seek track** that moves the playhead through the recording (`A11`). The control is the attachment's leading element and the
one thing that says *this can be listened to*: a filled `primary` circle (40 dp) carrying an `onPrimary`
lucide `Play`/`Pause` glyph (22 dp) inside a 48 dp `IconButton` target — the shape the device's own
voice notes already use — and which of the two glyphs is drawn comes from the **device player**, not
from the tap that asked for it: a recording that ends on its own, fails, or is replaced returns this
control to *Play* by itself. While an accepted recording's bytes are being read for the first time the
same target shows a `primary` progress ring in place of the circle, because that read goes through the
API and a tap still in progress must not look like a tap that did nothing (`BR-042`); a control that
cannot be used is filled `surfaceContainerHighest` with an `onSurfaceVariant` glyph rather than drawn as
one that would work if it were tapped. The attachment keeps a `secondary` fill in `shapes.medium`, a
4 dp inset and a 56 dp least height, so the circle and the controls beside it sit comfortably in a row
(`BR-012`). The position beside it states **where the recording is and how long it is** — `0:00 / 0:18`
before anything has played — in the same `bodyLarge`/`FontWeight.Medium` treatment the sheet's review uses,
where the total is the one the **API read from the recording's own container**, so one
recording does not state its length two ways
(`ADR-018` A3, `BR-041`, `A11`); the phase badge is the vocabulary photos and audio share,
drawn exactly as the gallery, the tray and the review draw it (`A4`). The technician's note is read
under the container with the shared note treatment, so a recording's note and a photo's note cannot read
differently. The removal control is the audio kind's own Manager-level action: it is drawn only for a
session the API would let perform it, it reports its own progress while the API answers, and it asks for
the same confirmed, reason-carrying dialog the photo removal does, with the audio kind's own wording and
tags (`BR-089`, `A7`). A recording the manager takes out of ordinary use stops playing.

**The position is a track the technician can move, and it carries no amplitude** (`ADR-018` A11, decided
2026-09-17; `docs/tracker/035-android-audio-evidence.md` Phase 9e). The attachment's second line is the
shared `JobAudioSeekTrack`: the platform's Material 3 `Slider`, drawn with the theme's own colours rather
than the library's default track — a 6 dp line in `onSurfaceVariant` at 30% with the played part and the
thumb in `primary` — inside a 48 dp interaction height, so the line stays thin while the target is as
comfortable as the play control's (`BR-012`). The fill follows the recording as it plays and follows the
thumb as it is moved, and a recording is moved **as the thumb moves** rather than when it is released,
including through the action a screen reader uses to move a slider (`BR-042`). **No waveform is drawn**: the
design's *"appropriate audio icon/waveform"* (`Figma/…/servora-job-details-spec.md` §11) remains undrawn,
which is what *no amplitude* means here. A recording the device player does **not** hold — every recording
before its first play, and every other entry while one plays — is drawn **quietly**, its track and fill in
muted `onSurfaceVariant` with the thumb disabled, because a control that cannot be used is not drawn as one
that would work if it were touched (`BR-042`). One recording is moved at a time, and it is always the one the
entry names.

**The Add update sheet's review plays the take back too, and states where it is** (`ADR-018` A9, A11,
`docs/tracker/035-android-audio-evidence.md` Phases 9c/9e). The review row's leading element is that same
play/pause control — one control, one player, on both surfaces that play a recording — followed by the take's
**position** in the same `bodyLarge`/`FontWeight.Medium` treatment the timeline states it with (its total
being the length this device measured, `BR-041`), and under the row the same `JobAudioSeekTrack` the timeline
draws, so one recording is read one way on both surfaces. The row keeps the 56 dp height the kind's other
states keep — the track is drawn below it, in its own 48 dp interaction height — so switching between
*Record*, the live microphone and the review does not move the foot of the sheet. Playing a take
back needs no capability and no connectivity: the bytes are on the device. **Stop** still ends the
recording; **Delete** still drops the take; only the glyph that reviewed it became the control that
plays it.

**A recording the API has not accepted is reported by a notice, not a tray** (`BR-091`, `ADR-018`,
`docs/tracker/035-android-audio-evidence.md` Phase 9b). The sheet adds one update at a time, so a Job
holds at most one unattached take and there is nothing to page through: the notice is a
`surfaceContainerHigh` surface with the same elevation as the tray, its heading **Audio to save** in
`labelLarge` bold on `onSurfaceVariant`, and one row per take. A row is `ic_mic` in `primary`, the take's
length (`0:18`, the format string the review uses, so the two cannot read differently), and under it
either the upload state a submitted take reports — the shared `evidence_state_*` labels the tray reports,
because "waiting to upload" is one idea in either kind (`BR-041`) — or **Not attached yet** for a take the
technician has not attached. Its actions are the take's own and no more: **Attach to update** as a
`TextButton`, drawn only while the take is unattached (the notice is where an unattached take is visible
once the sheet is closed, so it has to be finishable from there — `BR-012`, `BR-014`), and an 18 dp
`ic_close` `IconButton` that discards an unattached or refused take. A queued or retrying upload draws
neither, because the API may still accept it. While the device holds unaccepted evidence of either kind,
the notice and the tray are stacked at the bottom of the screen and the floating **Add update** action is
not drawn: the next thing the technician wants is to finish or drop what they have (`BR-012`).

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

**A `ModalBottomSheet` is a window of its own**, so the shell's inset above does not reach it — and it
does not need to, because the sheet window lifts its content above the keyboard itself. Material 3's
sheet dialog is edge-to-edge (`ModalBottomSheet.android.kt` calls
`WindowCompat.setDecorFitsSystemWindows(window, false)` and, from API 30, sets
`SOFT_INPUT_ADJUST_NOTHING`, so the IME does not resize the window), and the sheet pads its content
with `BottomSheetDefaults.windowInsets` — `WindowInsets.safeDrawing` restricted to the top and bottom
sides, and `safeDrawing` is `systemBars ∪ ime ∪ displayCutout`. The keyboard's height is part of that,
so a sheet's content is already laid out above it and the inset is consumed above that content: a
sheet's content must **not** add `imePadding()` of its own (it would add nothing), and the
`navigationBarsPadding()` a sheet's content carries is covered the same way.

What a sheet must provide is a **content area that can shrink** — the body scrolls
(`verticalScroll`/`LazyColumn`) — and, beyond that, **its actions must not live inside that shrinking
region**. The body is a `Column` with `Modifier.weight(1f, fill = false)` and the action row is the
sheet's last non-scrolling child: while everything fits, `fill = false` keeps the sheet short and the
row sits under the body; when the keyboard shortens the sheet, the body is capped by what is left and
scrolls, and the action the user is confirming stays on screen. An action *inside* the scrolling body
is the defect reported by product ownership on 2026-09-28
(`docs/tracker/057-qa-issue-list-visit-workflow.md` §3): the text was visible while typing and the
Save/Cancel row beneath it was not, so the keyboard had to be dismissed to reach it. The Visit
completion sheet, the request-another-visit sheet, the Job Details photo review panel and the Job
Update sheet's note and audio kinds follow that shape; the sheets with no field at all (schedule a
Visit, assign technicians) carry no keyboard and are unchanged, and the schedule's technician filter
sheet already keeps its list and its Apply/Clear footer in exactly that arrangement.

## Collapsible section headings (disclosure)

A section whose own rows can grow long enough to push the rest of the screen out of sight puts its
heading behind a disclosure, so the user folds the section away rather than losing the information:
the heading stays, and it keeps the section's count. The manager home's **Needs attention** section
is the first adopter (`docs/tracker/016-android-manager-home.md`); the customer detail's
archived-Property disclosure uses the same chevron and rotation inside a section rather than as a
heading; and the Job Activity photo gallery uses it for a section whose height grows with the Job's
evidence (`docs/tracker/032-android-job-activity-photo-ux.md`).

The same disclosure also heads **a group inside a section** rather than the section itself, which is how
Job Activity folds one Visit's updates away from another's: the heading is the group's own row, it states
the group's subject, and only that group's rows are hidden
(`docs/tracker/045-android-job-activity-visit-groups.md`).

| Part | Token |
| --- | --- |
| Heading label | `colorScheme.onSurfaceVariant`, `labelSmall`, bold, from `section_count_format` (`"Label · n"`) — the same string the always-open sections' labels use |
| Chevron | `colorScheme.onSurfaceVariant`, `ic_chevron_right`, 18 dp, rotated 90° while the rows are shown |
| Touch target | the whole heading row, `≥ 48 dp` (`Modifier.heightIn(min = 48.dp)`) |
| Ripple | clipped to `shapes.medium`, so the feedback stays inside the row |
| Chevron content description | the action, not the state: "Show the items that need attention" / "Hide the items that need attention", localized (`BR-028`) |
| State, where the heading is a row inside a section | `stateDescription` — `Expanded` / `Collapsed`, localized, so the state is announced as well as the action (`BR-028`) |

Only the section's own rows are hidden. Its heading, its count and the sections around it stay
exactly where they were, and a section with nothing to show carries no chevron and no click.
Whether a section starts expanded or collapsed is presentation, and belongs to the screen that owns
the section (`BR-042`).

## Customer detail — the contacts card

A customer's contact persons are their own card on the customer detail, between the customer's own
contact details and its Properties (`BR-095`; `ADR-022` D6). Product ownership approved the layout in
place of a Figma screen, because no design draws a contacts section.

| Part | Presentation |
| --- | --- |
| Section label | The shared `SectionLabel` — **Contact persons** with the count the API returned (`section_count_format`) |
| Card | The shared `InfoCard`: one contact per row, `outlineVariant` dividers between rows, and the add action behind a divider at the foot |
| Contact row | 2 dp between lines: the person's whole name (`titleSmall`, semibold, single line with ellipsis) with the **Primary** badge at the row's end when the contact holds the flag; then the person's own **phone** and **email** as the shared `CustomerContactLine`, and the row's actions |
| The customer's own phone | The customer's own contact card marks its **phone line** with the same **Primary** badge when **no** contact person holds the flag — the customer is then its own effective primary (`BR-095`). While a contact person is flagged, the customer's line carries no marker: it is the customer's general line (`ADR-022` D2, D10) |
| Primary badge | `shapes.small`, `colorScheme.secondary`, `onSurfaceVariant` text inside a 1 dp `onSurfaceVariant@25%` border, `labelSmall` bold — the badge an archived Property wears, so a state reads as a state |
| Phone / email links | `dialIntent` / `mailIntent` / `startContactIntent`, the affordances the customers list already has. A value the office never recorded is left out rather than drawn as a row announcing its absence (`BR-012`) |
| Row actions | Two `TextButton`s at the foot of the row — **Edit** in `primary` and **Remove** in `colorScheme.error` — each drawn only for the capability that would perform the write (`customers.contacts.edit`, `customers.contacts.remove`; `BR-007`, `BR-011`). **Remove** is disabled while a removal is in flight, so one tap cannot become two |
| Add action | A `TextButton` — **Add more contact persons** — drawn only for `customers.contacts.create`, behind an `outlineVariant` divider at the card's foot |
| Empty state | The card states *No contact persons recorded.* rather than drawing an empty surface; the add action stays for a session that may record one |
| Removal confirmation | A Material 3 `AlertDialog`: **Remove this contact person?** over a body naming the person and stating that the record is kept, with a text **Cancel** and a **Remove** action in `colorScheme.error`. Removal is soft (`ADR-022` D8), so the consequence is stated before it is taken |
| Refused removal | The shared dismissible refusal surface (`ActionAttention`) under the card, carrying the API's own reason in the contact vocabulary (`BR-042`) |

Reading the card adds no capability: a customer's contacts travel in the projection `customers.view`
already authorizes, so the card is drawn for every session that may read the customer and only its actions
are gated (`ADR-022` D4). Its form is the Add/Edit Contact destination — the Add Property form's sibling:
one `PropertyFormSection` holding the four fields a contact owns (first name, last name, phone, email) and
the primary row, whose whole row is the target and whose checkbox reports the choice, as the schedule's
filter rows do. That row is disabled while the contact already holds the flag, because the API documents
`isPrimary: true` and defines no un-promotion, so there is no change for the form to state. Edit Customer
shows the same contacts read-only, with the hint that they are managed from the customer detail screen
(`ADR-022` D5).

## Job Details — the Contacts section

The Contacts section answers *who do I reach, and how* (`BR-092`, `BR-095`; `ADR-022` D6, D10;
`docs/tracker/052-android-technician-job-details-redesign.md`). It presents **one** contact and folds every
other way of reaching the Customer away, so the person a technician came for is not buried under the
Customer's whole contact book (`BR-012`).

| Part | Presentation |
| --- | --- |
| Customer row | the card's first row (`OverviewRow`): the label **Customer** over the Customer's name, which opens the office Customer destination for a session whose read the API accepts and is inert for a technician, who holds no `customers.view` (`BR-011`, `BR-092`) |
| Effective primary contact | the block under that row: the primary's own **name** (`bodyLarge`, semibold) with the shared **Primary** badge as its trailing control (`BR-095`), then that person's phone and email as `CustomerContactLine`s — the app's own contact affordance, a 12 dp `primary` glyph and the value in `primary`, the whole line the target — opening the dialer (`ACTION_DIAL`) and the mail client (`ACTION_SENDTO`). A value the office never recorded is left out rather than drawn as an empty line (`BR-012`) |
| Who the primary is | the contact person the API flags (`isPrimary`), or the **Customer itself** when no contact person is flagged — a legal state, and then the Customer's own name, phone and email are the block. Nothing is promoted to fill the gap: a flagged contact with no number leaves the block without one rather than handing the role to the Customer's own line (`BR-095`, `BR-041`) |
| The marker, when the Customer is its own primary | the **Primary** badge rides the **first value the Customer recorded** — its phone, or its email when it has no phone — rather than a second statement of the Customer's name, because the row above already names it and one card does not state one value twice (`BR-012`). A Customer with no recorded value and no contact persons draws no block at all: the row above is the whole answer (`BR-095`) |
| Other contacts | the disclosure under the block: a heading — *N other contacts* from the `job_details_customer_other_contacts` plural — with an `18 dp` `ic_chevron_right` rotated 90° while open, `stateDescription` announced, the whole row the target at `≥ 56 dp`, and the convention above for the chevron's content description (`BR-028`, `BR-011`). It starts **closed**: nothing about the others is composed until it is opened |
| One other contact | a row per entry: the owner's name (`bodyMedium`, semibold), then its own phone and email as the same tappable `CustomerContactLine`s (`BR-095`, `ADR-022` D6). The entry that stands for the Customer's own general line is named after the Customer and carries the Customer's own values |

The section draws exactly what the read gave it: a session the API did not admit to the Customer receives no
contact block and is therefore shown nothing, because whether it may read the Customer is the API's answer
and never the client's (`BR-001`, `BR-007`).

## Home screens — the manager’s and the technician’s

Servora has two homes, and they answer different questions (`BR-010`, `BR-012`; `ADR-019` D6): the
manager’s asks *what is happening across the business?*, the technician’s asks *what do I need
to do next?*. They therefore share **presentation** and not **hierarchy**.

| Shared, because it is the same fact | Manager home | Technician home |
| --- | --- | --- |
| Greeting and date in the shell’s one contextual top bar | `homeHeader` (`HomePresentation.kt`) | the same header |
| A Visit’s status, with the derived overdue condition | `HomeVisitStatusPill` | the same pill |
| The scheduled time, formatted in the device’s locale | `formatScheduledTime` | the same |
| A section label with its count | `SectionLabel` over `section_count_format` | the same |
| Bordered cards, empty states, failure with retry, the last-reported notice | `InfoCard`, `OfflineNotice` | the same |

Two of these are worth stating because they are easy to get wrong. **The status pill is the home’s
own**, not the status chip in `ui/components`: that one presents a *status*, while a home row also
presents the one *derived condition* over a Visit’s schedule, which takes the backend’s answer
rather than comparing the schedule against the device clock (`BR-001`). And **the day both screens
describe is resolved by the API** from the `timeZone` the client names, so the manager’s screen and
the technician’s cannot disagree about which Visits are today’s (`BR-041`).

**When the day is read.** Both homes are reads over records the backend owns (`BR-001`), so the day is read
when Home is entered **and again whenever the bottom-navigation area is entered again** — a return from a
Job, a customer or any other drill-down (`docs/tracker/055-android-home-refresh-on-return.md`). Coming back
is the moment a change the technician just made becomes visible, so the screen asks for the day rather than
continuing to describe the one it was left with; the day already known stays on screen while that read is in
flight, a read already in flight is not started twice, and a read that cannot reach the backend is served
from the last-reported copy and marked as such (`BR-013`, `offline-first-architecture.md` §7).

What the technician home does **not** copy from the manager home:

- No counts, KPIs or dashboard summary: the screen leads with the **next Visit** (the largest thing on
  it, with the time first and one primary action that opens the Job), then **today** in chronological
  order, then its **Needs attention** section, then a capped **upcoming** preview whose heading reports
  the whole count.
- Its **Needs attention** section is *absent* rather than empty when there is nothing to act on: an
  all-clear card would compete with the next Visit for the same glance (`BR-012`). The manager
  home’s all-clear card stays, because there the absence of exceptions is itself the answer. It is
  drawn **before** the preview of the days after today, because a late attempt is work to resolve rather
  than work to look forward to, and it never repeats a Visit the home already states: the Visit to do
  next, and today's own rows, carry their own derived condition, so the section holds the late work the
  screen says nowhere else — an attempt from an earlier day that is neither. That is why it can be
  absent while the caller still has something overdue (`BR-001`, `BR-041`).
- No status control and no scheduling affordance: changing a Visit’s status is a field **write**
  (`BR-074`, `BR-066`) and belongs beside the Visit on the Job’s screen, which is where every row
  of this home leads (`BR-012`).

## Manager Schedule — the week strip and the day's agenda

The schedule is a **dispatch** screen, not a calendar (`Figma/src/imports/pasted_text/servora-scheduler-design.md`;
`docs/tracker/038-android-manager-schedule.md`, `docs/tracker/040-android-manager-schedule-density.md`).
Its top section is persistent — the agenda below it scrolls on its own — and it is arranged by what a
dispatcher needs in what order: the day, then the dates around it, then one row holding the two things
that narrow the day. Every control sits at the smallest size that still reads and still hits, because
the height it takes is height the day's work does not get.

| Part | Presentation |
| --- | --- |
| Date header | The selected day written in full in the language's own order (`MMMMdyyyy` resolved by the platform) with an 18 dp `ic_chevron_down`, on a target of at least 48 dp, opening the platform's month calendar. A **Today** text button appears only when another day is selected. |
| Week strip | A `HorizontalPager` of weeks, 48 dp tall, one page per week. Each day is a 44 × 48 dp cell: the abbreviated weekday (`EEE`) in `labelSmall` `onSurfaceVariant` over the day number in `titleMedium` bold, with a 5 dp marker dot. The selected day is filled `primary`/`onPrimary`; the marker is `primary` on an unselected today and `onPrimary` inside the selected fill, so "today" and "selected" stay two different facts. Swiping moves the strip a week at a time; tapping a day selects it. It is the part that **collapses** (below). |
| Control row | One 48 dp row under the strip holding the lane selector and the technician chip, 8 dp apart, because both are secondary to the agenda and 48 dp is the smallest a control can be hit at. Each control draws a 32 dp surface inside its own target — the Material "target 48, surface smaller" arrangement — so the row reads as filtering rather than as a pair of actions. |
| Lane selector | A compact segmented control: two segments the width of their own labels, 2 dp apart (`selectableGroup`, `Role.Tab`). The lane in effect carries a 32 dp `shapes.large` pill in `primaryContainer`/`onPrimaryContainer` with `labelMedium` semibold text; the other is quiet `onSurfaceVariant` text with no fill. The Unassigned segment carries the API's own total as a rounded badge — `error`/`onError` while unselected, `primary`/`onPrimary` while its pill is selected — drawn only when the count is not zero. |
| Field schedule view selector | **The same control is My Schedule's view selector** (`docs/tracker/057-qa-issue-list-visit-workflow.md` §5.8): two segments — **Schedule** and **Requests** — drawn to the lane selector's own metrics (32 dp `shapes.large` pill, 2 dp apart, inside a 48 dp target, `selectableGroup` of `Role.Tab`), the view in effect taking `primaryContainer`/`onPrimaryContainer`. The Requests segment carries the count of the caller's own requests the office has still to answer, as a rounded badge — **`secondary`/`onSurfaceVariant` while unselected and `primary`/`onPrimary` while its pill is selected** — drawn only when the count is not zero. It is deliberately quieter than the office screen's Unassigned total: an unanswered request is the office's to act on rather than the technician's, so the count informs instead of alarming (`BR-012`). **No date control is drawn beside the requests view**, because nothing on it is scoped to the selected day: a request is a proposal about another attempt (`BR-FV-002`), and a week strip over undated content would say otherwise. |
| Technician chip | A 32 dp `shapes.large` chip inside a 48 dp target, holding a 16 dp `ic_users`, the filter's own summary and an 18 dp `ic_chevron_down`. `secondary`/`onSecondary` with a 1 dp `outlineVariant` hairline while the whole organization is shown; `primaryContainer`/`onPrimaryContainer`, semibold and borderless, once a filter is in effect — so a narrowed day is visible without opening anything. |
| Technician sheet | A `ModalBottomSheet` titled **Technician** with a one-line subtitle, a **filled** 44 dp search field (`secondary`, no outline) that reads as part of the list beneath it, then a `LazyColumn` (320 dp at most, so its own actions stay on screen for a long team): `All technicians` first, then the technicians the API returned. Every option is a checkbox row of at least 48 dp — the row owns the tap (`toggleable`, `Role.Checkbox`) and the checkbox reports it — tinted `primary` at 10% while chosen, and **selected options are listed before the rest** so "who did I pick?" is answered at the top. The search narrows what is *offered* and never what is *chosen* (`BR-012`), `All technicians` is offered while searching because clearing a filter is not the same question as finding a technician, and a search that matches nobody says so in the sheet's own words. Its actions are a `Clear` `OutlinedButton` (shown only while something is chosen; it empties the draft and asks for the whole organization at once, staying open) and an `Apply` `Button` reading `Apply (n)`, which commits the draft and **closes** the sheet — the same draft/apply route the customer filter sheet takes (`BR-068`, `BR-067`; the closing behaviour was reported missing from physical-device QA and fixed 2026-09-17). |

**The strip collapses, the controls do not.** Once the agenda is scrolled past 16 dp, the week strip
animates away and the date header and control row stay: the day is still named, still changeable and
still filterable while the day's work has the screen. The strip is clipped rather than disposed, so
expanding it returns to the week the manager was on, and moving to another date returns the agenda to
the top of that date — which brings the strip back with it. The top section therefore costs 144 dp at
rest and 96 dp while scrolling, against 206 dp before
(`docs/tracker/040-android-manager-schedule-density.md`).

The agenda's cards reuse the home's presentation rather than restating it: `HomeVisitStatusPill` for
the Visit's status with the derived overdue condition, `formatScheduledRange` for `9:00 – 10:00`,
`formattedAddressLine` for the preserved address and `techniciansSummary` for the crew — which is why
the crew summary was extracted from the manager home instead of copied. A Visit whose window is not set
says **Not scheduled** (`job_details_not_scheduled`); a Visit with nobody on it shows
`customers_job_unassigned` marked with a 6 dp `error` dot beside the technician, because the absence of
an assignment is not a status (`BR-042`). The card never shows the **Job's** status: Job status and
Visit status are separate state machines (`BR-059`, `BR-074`).

### What one row of the agenda is

A day is scanned in a second or two, so the card states its facts in the order a dispatcher asks for
them, and the lane says that its rows are one chronology rather than a stack of unrelated cards.

| Part | Presentation |
| --- | --- |
| Timeline rail | A 12 dp leading column: a 2 dp `outlineVariant` rule with a 7 dp dot in the Visit's own status colour — the colour the status pill already wears (`BR-041`). The rail is chronological structure, not a second status display, so the dot stays small and the rule stays the quietest line on the row. The rule is drawn through the 8 dp gap between rows, so the lane reads continuously, and it is cut at the first row's dot and the last row's dot, so the lane never looks as though it continues past what is on screen. A row states a time; it is **never drawn as tall as its duration** (`BR-012`). |
| Visit card | The whole card is the touch target and it opens the Job (`BR-012`). Its lines are, in order: the **time window** in `titleSmall` bold with the **status pill** at the row's end (`HomeVisitStatusPill`); the **Job title** in `bodyLarge` medium, two lines at most; the **customer**, with the Property's name after it (the localized `%1$s · %2$s` format) when the address snapshot carried one; the **crew**; and the **address** behind a 16 dp `ic_map_pin`. Time and Job title are the strongest elements, customer/Property is secondary, and crew and address are tertiary metadata. Every line ellipsizes rather than growing the card, and a crew longer than one line names two members and counts the rest (`%1$s +%2$d`), so a Visit with five technicians is never presented as one with two. 12 dp of padding and 2–4 dp between lines carry the card's density. |
| Now cue | A 28 dp row drawn only on the day the manager is living through and only in the Schedule lane: `primary` at 40% for the rule, the label (`Now · 2:35 PM` / `Maintenant · 14 h 35`) in `labelSmall` bold `primary` between two hairlines, and a 10 dp `primary` dot on the rail. It separates the Visits that have started from the ones that have not, and it is the **one dot on the rail that is not a Visit's** — larger and in the brand colour, so "where the day is now" is never read as a Visit's status. It is the **device's** clock, read again every 30 seconds while the screen is open, and it decides nothing: a Visit's status and its overdue condition stay the API's answers (`BR-001`). |
| Empty states | One bordered `shapes.large` card, 16 dp inset, a `bodyLarge` semibold heading over a `bodySmall` `onSurfaceVariant` line: **Nothing scheduled** / *Nothing is scheduled for this day.* in the Schedule lane, and **All visits have been assigned** / *Nothing is waiting for a technician.* in the Unassigned lane. |

**No chevron is drawn on the card.** It was considered and rejected on measurement rather than taste: on
a 360 dp phone the time window and the status pill together nearly fill the row, and the 26 dp a trailing
chevron costs would force the time — the first thing a dispatcher reads — to ellipsize. The card is a
bordered, clickable `shapes.large` surface with the platform's ripple and it opens the Job, exactly as
the manager home's Visit row does, so the two screens describe the same row the same way (`BR-041`).

Metrics, from this document's own scale: the 20 dp gutter and 16 dp card-gutter the homes use, 12 dp
inside a schedule card, an 8 dp gap between rows, a 12 dp rail with a 6 dp gap to its card, and a target
of at least 48 dp on every control — reached by keeping the target 48 dp and drawing the surface
smaller rather than by shrinking the target.

## Technician Schedule — my own day, my own week

The technician's schedule is a **field** screen, not a dispatch board
(`docs/tracker/041-android-technician-schedule.md`; `BR-010`, `BR-012`). It answers "what is assigned to
me, and what am I doing next?" — so it presents one day of the caller's own Visits and a week strip to
look ahead, and it deliberately has **no** lane selector, **no** technician filter, **no** month calendar
and **nothing that assigns, reschedules or cancels**. The week strip and the day cells are the same
shared component the manager schedule uses (`ScheduleWeekStrip`), because which day a swipe reaches and
what a tap selects is one question on both screens (`BR-041`); everything around it is the technician's
own.

| Part | Presentation |
| --- | --- |
| Header | One 48 dp row: an `IconButton` with an 18–20 dp `ic_chevron_left`, the **week** the strip is on written in the language's own order (`Sep 14–20` — the month is repeated only when the week crosses one), an `ic_chevron_right` `IconButton`, and a **Today** text button that appears only when another day is selected. The arrows move the strip a week at a time, which is the same movement a swipe makes, so a pointer target exists for every gesture. |
| Week strip | The shared `ScheduleWeekStrip`: 48 dp tall, one page per week, 44 × 48 dp day cells, the selected day filled `primary`/`onPrimary`, today carrying a 5 dp marker dot. |
| Day heading | A `labelSmall` bold `onSurfaceVariant` line over the agenda: `Today · Wednesday, Sep 16`, `Tomorrow · …`, or the date alone when the technician browsed further. It is the strip's selection spelled out, so a swiped-away day is never guessed at (`BR-041`). |
| Last-reported notice | The shared `OfflineNotice` (`secondaryContainer`) at the top of the agenda when the day on screen is the one the backend last reported rather than a current answer (`BR-013`). |
| Visit row | A start-time column (56 dp, `titleSmall` bold, top-aligned with the card's padding) beside the card, so a day is read down the times. The whole card is the target and opens the Job. |
| Visit card | `shapes.large`, 12 dp padding, 4 dp between lines: the **Job title** (`titleSmall` semibold), the **customer** with the Property's name after it when the snapshot carried one, the **address**, then a row holding the shared `HomeVisitStatusPill` and the **time window**, and finally the **crew** line behind a 14 dp `ic_users`. Every line ellipsizes; the address may take two lines. |
| Crew line | `You` when the caller is the only one on the Visit, `You + 2` when others are assigned with them (`%1$s +%2$d`), and the crew's own names when the caller is not on it — the caller is recognised by the membership the API resolved the read for, never by a name the device guessed (`BR-068`, `BR-041`). |
| Emphasis | The **first Visit that has not completed** carries a 2 dp `primary` border; every Visit already `COMPLETED` is drawn on `surfaceVariant` with muted lines. The emphasis is presentation only — no status is derived from it — and it answers "what is left" at a glance without hiding what is done (`BR-012`). |
| Empty day | One bordered `shapes.large` card, 24 dp vertical inset: **No visits assigned** over *Nothing is assigned to you on this day.* |
| Loading / failure | A centred spinner with *Loading your schedule…*, and a centred failure state (*Couldn't load your schedule* over the shell's generic error line) with a **Try again** action — the day is never presented as an empty one when nothing could be read (`BR-042`). |

Nothing on this screen opens the manager's surface, and nothing on it changes a Visit: a technician acts
on their work through the Job Details and Visit flows the rest of the app already provides (`BR-066`).

### My Schedule — Requests, and one request's details

The **Requests** view of My Schedule is the technician's own record of the extra visits they asked for
and of what the office did with each of them (`BR-FV-012`, `BR-FV-013`;
`docs/tracker/057-qa-issue-list-visit-workflow.md` §5.8, §5.11, §5.12). It is a **record**, not a
dispatch board: nothing on it reviews a request, and a request is drawn as a proposal rather than as an
appointment (`BR-FV-002`, `BR-FV-010`).

| Part | Presentation |
| --- | --- |
| View selector | The shared field view selector — **Schedule** and **Requests** — described in the manager schedule's table above. |
| Request row | A bordered `shapes.large` surface, 12 dp padding, 2–4 dp between lines, and the whole card is the touch target: it opens the **request details** destination, not the Job. Its lines are the office's decision as a `RequestStatusPill`, the window the technician **proposed** (`You proposed · Sep 9, 9:00 a.m.` / `Vous avez proposé · …`, or `You proposed no time` when none was named), the reason they gave another attempt was needed, the same-technician line behind a 14 dp `ic_users`, and the office's note when the office wrote one. Every line ellipsizes rather than growing the card. |
| Conversation on the row | A request the office returned draws the same `FollowUpRequestConversation` the office's card draws, and — while an answer is owed — the **Answer** action at the row's foot, disabled while one is on its way. |
| Request details | One pushed destination, titled **Request details** / *Détail de la demande*, with the standard back control. The request's own record is a bordered `shapes.large` card holding the status pill and the proposed window on one row, the reason in `bodyLarge` medium, the same-technician line, and the office's note; the conversation follows on its own card under a `labelSmall` section label (*With the office* / *Avec le bureau*), and the actions sit at the foot, right-aligned: **View job details** / *Voir l'intervention*, then **Answer** while one is owed. |
| Details states | Loading is a centred spinner over *Loading your requests…*; a failed request read is a centred failure with a **Try again** action, because the request is read out of that list; and a request the caller's own read does not hold is stated plainly (*This request isn't available* / *Cette demande n'est pas disponible*) with nothing invented in its place (`BR-042`). A list that is on screen as the last one the backend reported carries the shared `OfflineNotice` above the record (`BR-013`). |

The details destination leaves the Job as a **pushed** screen, so Back returns to the request the
technician was reading; it never states a request in a Visit's vocabulary and never presents one as
scheduled work (`BR-047`, `BR-059`, `BR-FV-002`).

