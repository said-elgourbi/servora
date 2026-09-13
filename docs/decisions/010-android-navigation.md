# ADR-010 — Android navigation and the secondary screen header

**Status: Accepted** (product-owner instruction, 2026-09-12)

> **Revision (2026-09-13).** D4 below — the `SecondaryScreenHeader` drawn below a global **Servora**
> header — is superseded by `docs/decisions/011-android-contextual-top-bar.md`. The back stack and the
> destination graph (D1–D3, D5) are unchanged and still authoritative.

Supersedes nothing. Extends `ADR-006` D8, which recorded that the Android client had no navigation
library: that remains true of the pre-session flow, which is still a state machine, and stops being
true of the signed-in area, which now has one because it has a back stack to keep.

References: `BR-001`, `BR-007`, `BR-028`, `BR-041`, `BR-042`, `Project.md` §6, §9, §11, §23 and
§31, `dev.md` §2, §4, §9 and §15, `qa.md` §6 and §13,
`docs/design/android-design-system.md`, `docs/tracker/010-customer-detail.md`.

## Context

The signed-in area had no navigation component. `ServoraHomeScreen` held the selected
bottom-navigation tab in a `rememberSaveable` value (`DashboardTab`), and `CustomersScreen` held the
drill-down in two more `rememberSaveable` values (`selectedCustomerId`, `mode`). There was no
`NavHost`, no `NavController`, no back stack, and the only `BackHandler` in the app sat in the
pre-session `AuthDestinations`.

Three user-visible defects followed from that:

1. The customer detail was labelled **"Customers"** — `CustomerDetailTopBar` drew a `TextButton`
   whose label was the parent section name (`R.string.nav_customers`).
2. That control had no back arrow, so the screen did not read as a screen below the Customers
   section.
3. Android's system Back button had nothing to pop, so the Activity's default applied and the app
   exited instead of returning to the Customers list.

The product owner's instruction is to use standard Navigation Compose — `navigateUp()` /
`popBackStack()` — rather than extend the hand-rolled state, and to reuse one shared secondary
header instead of repeating it per screen.

## Decisions

### D1 — The signed-in area gets a Navigation Compose back stack

`androidx.navigation:navigation-compose` is added (version pinned in
`gradle/libs.versions.toml`). The dependency is not decorative: the back stack is what makes system
Back and the header arrow pop the same screen, and `BR-001`'s "clients remain replaceable" is
unaffected because navigation decides only which screen is on top, never business state.

Rejected alternatives:

- **Keep the state flags and add a `BackHandler`.** Explicitly ruled out by the instruction, and it
  would put a hand-maintained history in the shell that every new screen would have to remember to
  update.
- **A custom back stack list in a ViewModel.** Reimplements `NavController` for no gain
  (`dev.md` §4, §18).
- **A third-party navigation library.** The Jetpack one is the platform component, and the project
  already builds on AndroidX and Compose.

### D2 — The root destination holds the bottom-navigation area; drill-down screens are pushed on it

The graph has one start destination, `ServoraRoutes.ROOT`, whose content is the bottom-navigation
area the shell passes in. Every other destination — View Customer, Edit Customer, Create Customer,
Job History — is pushed *on top of* it.

This is what makes each requirement fall out of the platform rather than out of custom code:

- Opening a customer pushes a destination, so nothing on the stack is replaced.
- The header arrow calls `navigateUp()`; system Back is handled by the `NavHost` itself. Both pop
  exactly one destination, so they cannot disagree.
- Pressing Back on the root destination has nothing left to pop, so the Activity's default applies
  and the app leaves. That is the only case in which it leaves.
- The Customers list is never re-created, so the `customers.view` gate and the filter the user
  applied are still there when the detail is popped.

Choosing a bottom-navigation tab pops any drill-down screen first
(`popBackStack(ServoraRoutes.ROOT, inclusive = false)`), so the back stack never points at a screen
the selected tab does not show.

### D3 — Each secondary screen is its own destination, including Job History

The customer's Job history used to be a `rememberSaveable` boolean inside the detail screen. That
had to go: with a real back stack, a boolean inside one destination is invisible to system Back, so
Back from Job History would have jumped to the Customers list instead of the detail.

Job History is therefore a destination of its own, and it covers its own loading and failure states
because a restored back stack can land on it without the detail destination being composed first.

### D4 — One shared secondary header

> **Superseded (2026-09-13) by `ADR-011`.** The shared `SecondaryScreenHeader` and the global
> **Servora** header it sat below were replaced by one contextual top bar owned by the app shell.

`ui/components/SecondaryScreenHeader.kt` is the single implementation of the screen header below the
global **Servora** header: a back control on the left, the screen's own title beside it, and the
screen's actions on the right.

It exists once because the instruction requires it and because the defect it fixes was a per-screen
mistake: the title names the screen itself and never the section it was opened from. The back
control is an icon, so its meaning is carried only by a localized content description
(`nav_back` / "Retour") for screen readers (`BR-028`).

Root destinations do not use it — they have nothing to go back to — and the global **Servora** header
and the bottom navigation stay in the shell, so they are drawn once and survive navigation.

### D5 — Business state stays with the ViewModel

The `NavHost` asks `CustomersViewModel` for a customer's detail when the destination needs it. The
ViewModel keeps skipping a customer it already holds, so navigation does not turn into a second
source of truth (`BR-001`). No route carries business data beyond the identifier the destination is
about.

## Consequences

- Every future secondary screen (View/Edit/Create Property, View/Edit/Create Job) is a new
  `composable` entry plus `SecondaryScreenHeader`, not a new navigation mechanism.
- The signed-in area's screen titles are now decoupled from the bottom-navigation section names, so
  `nav_*` strings are tab labels only.
- System Back behaviour is uniform because it is the `NavHost`'s, not each screen's.
- The detail's cached read is released when the bottom-navigation area is re-entered, which is the
  behaviour the previous `closeCustomerDetail()`-on-leave had.

## Verification

Back-stack behaviour is covered by `ServoraHomeNavigationTest` (instrumented): open → pushed detail,
header arrow → list, system Back → list, Edit → Back → detail, See all → Job History → Back →
detail, re-open → no duplicate destination, and system Back on the root leaves the app. The
secondary header's title and its back control's content description are covered by
`CustomerDetailScreenTest`.
