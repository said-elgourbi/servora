# ADR-011 — Android contextual top bar

**Status: Accepted** (product-owner instruction, 2026-09-13)

Supersedes `ADR-010` D4 (the secondary screen header drawn below a global **Servora** header).
Extends `ADR-010` D1–D3, whose back stack and destination graph are unchanged.

References: `BR-001`, `BR-007`, `BR-028`, `BR-041`, `BR-042`, `Project.md` §9, §11, §23 and §31,
`dev.md` §2, §15 and §18, `qa.md` §6 and §13, `docs/design/android-design-system.md`,
`docs/tracker/010-customer-detail.md`.

## Context

After `ADR-010` the signed-in shell drew a global header in its `Scaffold` — the brand name **Servora**
and an inert alert glyph — and each secondary screen drew its own `SecondaryScreenHeader` in the
screen's content, below it. Every secondary screen therefore showed two stacked rows:

```text
Servora                                  ← global header, always drawn
← View Customer              Edit        ← per-screen header
```

The global row was redundant, consumed a second row of vertical space above content that already had
a title, and weakened the screen hierarchy — the top row was the same on every screen, so it carried
no information about where the user was.

## Decisions

### D1 — The signed-in application has exactly one top bar

The shell's `Scaffold` owns the only top bar. `ManagerHeader` and the global brand row are removed;
no screen draws a header of its own. Content therefore begins directly below the one bar.

### D2 — One shared `ServoraTopBar` built on Material 3

`ui/components/ServoraTopBar.kt` holds the single implementation: a Material 3 `TopAppBar` with the
destination's title, an optional back control and optional trailing actions. It replaces the plain
`Row` that `SecondaryScreenHeader.kt` used to draw; that file is deleted.

Its arguments are supplied through one value object, `ServoraTopBarState`:

| Field      | Meaning                                                                       |
| ---------- | ----------------------------------------------------------------------------- |
| `title`    | The destination's own localized title, never the parent section's name.       |
| `isRoot`   | Whether the destination was reached directly from the bottom navigation.      |
| `onBack`   | The destination's navigation callback; a root destination passes none.        |
| `subtitle` | The destination's optional context line; `null` when it has none.             |
| `actions`  | The destination's contextual actions, empty when it has none.                 |

The bar keeps the design tokens the previous header used: `titleLarge`, bold, `onBackground`, a
steel-blue back control (`primary`), a `background` container, and Material 3's default touch targets
and status-bar insets.

### D3 — The navigation graph is the one place a destination's header is described

`ServoraNavHost.kt` exposes `servoraTopBarState(navController, rootState, permissions)`, which reads
`currentBackStackEntryAsState()` and returns the `ServoraTopBarState` for the destination on top. A
root destination uses the `rootState` the shell builds from the selected bottom-navigation tab
(`nav_home`, `nav_schedule`, `nav_customers`, `nav_settings` with `isRoot = true`); a pushed screen
uses its own title (`customers_view_title`, `customers_all_jobs_title`, `customers_edit_title`,
`customers_create_title`) and a back control.

The mapping lives in one function beside the graph, so no screen and no second top bar repeats it and
no route string is compared anywhere else. A future screen adds one `composable` entry and one branch
here.

### D3.1 — A destination may name the customer it belongs to

A screen whose meaning depends on which customer it was opened for shows that customer as the bar's
optional context line rather than putting a redundant heading inside its own content. Add Property is
the first such destination: its bar reads **Add Property** with the customer beneath it, exactly as
the approved design draws the header. The mapping resolves the name through the `customerName`
lookup the shell supplies, which reads the customer detail the destination itself has already asked
for, so the line appears as soon as that read answers and the header is never a second source of
business data (`BR-001`, `BR-042`).

### D4 — Back behaviour is unchanged and platform-owned

The back control calls `navigateUp()`; Android's system Back is handled by the `NavHost` itself, so
the two pop the same entry and cannot disagree. Opening a secondary screen still pushes a destination
rather than replacing one, a bottom-navigation tab still pops any drill-down first, and the app is
left only from a root destination. No manual history and no custom `BackHandler` are introduced.

### D5 — The global alert affordance is removed

The inert alert glyph in the removed header is deleted and notifications are not relocated: no
documented destination for them exists (`BR-029`, `BR-042`). The permission-gated **Edit** action on
View Customer is the only contextual action the graph currently supplies, and it is drawn only when
`permissions.canEditCustomer` is true.

## Consequences

- Every screen shows one contextual bar. Root screens show a title without a back control; pushed
  screens show their own title with one.
- Content moves up by the height of the removed row on every screen.
- A screen's title is decoupled from the bottom-navigation section name, so View Customer is never
  labelled **Customers**.
- The View Customer **Edit** action lives with the destination's header description rather than in
  the screen's composable, so permission gating is applied where the action is created.
- A Job History detail the old header showed as a trailing count (the number of Jobs) moves into the
  list, because it is information rather than a contextual action.

## Verification

`ServoraTopBarTest` covers the bar itself: the destination's own title, no back control on a root
destination, the localized back description and its callback, an empty actions area, and a rendered
action. `ServoraHomeNavigationTest` drives the real shell and covers the root title without a back
control, View Customer's own title with a back control, the **Edit** action's permission gating, and
the unchanged back-stack behaviour (arrow → list, system Back → list, Edit → Back → detail, re-open
without a duplicate destination).
