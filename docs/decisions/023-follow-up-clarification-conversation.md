# ADR-023 — A request's clarification conversation, and the answer that returns it to review

**Status:** Accepted (product-owner decision, 2026-09-29: the decision was given as the task that
produced this record — extend the technician-side *Follow-up Visit Request Details* flow into a
request-scoped, append-only clarification conversation in which a technician's reply atomically moves
`NEEDS_CLARIFICATION` → `PENDING` — and it answers option 1 of the open choice
`docs/tracker/057-qa-issue-list-visit-workflow.md` §5.3.3 recorded, under the `BR-040` change procedure)

Date: 2026-09-29

Tracker: `docs/tracker/057-qa-issue-list-visit-workflow.md` — §5.3.3 is the open question this record
decides, and §5.11 is the implementation

Rule produced: the `BR-FV-012` change record in `.clinerules/Business Rules.md` §12A Follow-Up Visit
Requests

Contracts: `docs/api/visit-requests.md` (the `reply` route, the lifecycle, the conversation, the offline
posture), `docs/domain/job-visit-domain-model.md` §11 (the conversation table and the reply transition)

Predecessors: `docs/decisions/020-manager-schedule-and-unassigned-lane.md` (the review lane, its
confirmation and the note a decision is taken with), `docs/decisions/014-android-offline-engine.md` (the
engine the reads travel in, and the posture D6 records)

References: `BR-001`, `BR-002`, `BR-006`, `BR-007`, `BR-009`, `BR-011`, `BR-012`, `BR-013`, `BR-014`,
`BR-028`, `BR-031`, `BR-032`, `BR-033`, `BR-041`, `BR-042`, `BR-067`, `BR-068`, `BR-FV-001`, `BR-FV-002`,
`BR-FV-004`, `BR-FV-005`, `BR-FV-012`, `BR-FV-013`; `Project.md` §9, §10, §13, §14; `dev.md` §6, §7, §14;
`qa.md` §3.1, §4.3, §13.

## Context

The office has been able to return a follow-up request for clarification since the review lane landed:
`POST /jobs/:id/visit-requests/:requestId/clarification` moves an open request to `NEEDS_CLARIFICATION` and
records the office's note on it (`docs/api/visit-requests.md`). Tracker 057 §5.2(a) recorded what that
left: the row disappeared from the lane, and once the lane was fixed (§5.5) the clarified request stayed
decidable — but **the person the question was addressed to could not answer it**. The technician could
read the office's note in the Requests tab of My Schedule (§5.8) and had no way to reply, because the
lifecycle had no transition back and the API had no route.

§5.3.3 put the decision to product ownership as two mutually exclusive options:

1. keep *Clarify* as an office status meaning *we need more from you*, **told to the technician**, who can
   answer it — "which needs the technician surface of (c) and a transition the API does not have today"; or
2. drop *Clarify* from the client and keep **Reject** with a reason, which would have been a business-rule
   change because `BR-FV-004` names "return the request for clarification".

Option 1 was decided on 2026-09-29. This record states what that means, because it is a change to the
`BR-FV-012` lifecycle a confirmed rule stated as `PENDING → APPROVED | REJECTED` with a clarified request
"unresolved until it is later approved or rejected".

## Decisions

### D1 — A returned request is answered into `PENDING`, in one operation

**Decided:** the lifecycle gains exactly one transition, `NEEDS_CLARIFICATION → PENDING`, taken by the
requester's answer. The answer and the transition are **one** business operation in **one** transaction:
the message is appended and the request returns to the office's review together. They are not separable,
because either half alone is a lie — an answer with the request still in `NEEDS_CLARIFICATION` would leave
the office unable to act on what it asked for, and a request back in `PENDING` with no answer recorded
would claim the requester said something they did not.

**Not decided, and not implemented:** nothing else changes. `APPROVED` and `REJECTED` stay terminal
(`BR-FV-012`), `NEEDS_CLARIFICATION` is not a decision, and no requester approves, rejects or reopens
anything. A request may be returned more than once: each return is a message, each answer is a message,
and the request alternates between the office's hands and the requester's as often as the work requires.

### D2 — The answer is the requester's own action, and only theirs

**Decided:** `POST /jobs/:id/visit-requests/:requestId/reply`, authorized by the capability that raises a
request (`visits.request_follow_up`), with the **request's own requester** checked in the service. A
request raised by another member is reported as **not found**, never as forbidden, so an id is never a way
to read or write someone else's request (`BR-007`, `BR-009`) — the same scope answer
`JobsService.listFollowUpVisitRequests` already gives a requester.

**Reasoning:** the capability answers "may this session raise and answer follow-up requests at all"; whose
request this is, is a fact about the row. Neither substitutes for the other, and a reviewer holding only
`visits.review_requests` is refused at the route (403) with its own error code rather than being told a
different story about the request.

### D3 — The conversation is request-scoped, append-only and stored one row per message

**Decided:** a new table, `follow_up_visit_request_messages` (`api/drizzle/migrations/0018_…`): the request
it belongs to, the author membership, the body, and the server time it was recorded. There is no update
path and no delete path, exactly as a Visit note has none (`BR-088`).

**Why a table rather than the request's own note:** `review_note` is a single, mutable column that each
decision overwrites, so it cannot hold an exchange. §5.3.3's option 1 asks for *a conversation*, and the
domain model already anticipated this: "If later product requirements need every
return-for-clarification/resubmission as separate events, that can be split into an append-only history
table without changing the rule that the request itself has one explicit current lifecycle state"
(`docs/domain/job-visit-domain-model.md` §11). This is that split, and the request keeps its one current
state, which is what D1 changes.

**The side a message belongs to is derived, not stored.** `authorKind` (`REQUESTER` | `OFFICE`) is computed
from the request's own `requesting_technician_membership_id`, so no second vocabulary for "who is who"
exists in the schema (`BR-041`, `BR-042`).

### D4 — The office's question is appended in the same transaction as the return

**Decided:** when *Clarify* is taken with a note, that note becomes a message of the exchange, written
inside the same transaction as the status change (which now locks the request row, so two reviewers
deciding at once cannot both satisfy the optimistic-concurrency check — `BR-032`). The request's
`review_note` keeps its existing meaning, and keeps being written.

**Why both:** the thread is the *history*; `review_note` is the request's *current* decision note, which the
office lane and the technician's own list already read, and removing it would break the surfaces that
report a refusal's reason (`BR-FV-013`). The duplication is deliberate and bounded: a card states the note
when the conversation does not already hold that sentence, and always states a terminal decision's note
(`showsOfficeNote` in `ui/schedule/FollowUpRequestConversation.kt`).

**A rejection appends nothing.** It is a decision that closes the request, not a turn in a conversation,
and its reason is the request's own note.

### D5 — The conversation travels inside the request projection

**Decided:** every projection of a request carries its `messages`, oldest first — the office's review read
(`GET /jobs/visit-requests`) and the requester's own read alike. No new read route, and no per-request
read: a list read batches them in one query (`dev.md` §6).

**Reasoning:** the exchange is the request's own business history (`BR-FV-013`), not a separate resource
with its own authorization question. Both readers are already authorized to read the request, and the route
that lists it is the route that answers one. A read route of its own would have created exactly the kind of
second authorization question §5.3.1 is still open about.

### D6 — The answer is online-only, and the thread is never a local guess

**Decided:** the answer is sent to the API — never queued in the outbox — and a failure is reported. The
conversation is read with the request read, which is already online-only
(`docs/tracker/057-qa-issue-list-visit-workflow.md` §5.8: "a stored answer could present a decision the
backend has since replaced").

**Reasoning:** `POST …/reply` accepts no client-generated idempotency key, and no conflict policy has been
decided for it (`BR-032`, offline standard §13.2), so queuing it would invent both. The posture is recorded
rather than assumed, and the reason the read is online-only applies to the answer for the same reason: an
answer the office cannot see is worse than no answer.

### D7 — Both surfaces read the same thread; the requester writes in its own composer

**Decided:**

- The technician's Requests tab draws the request's conversation and offers **Answer** while the request
  awaits one, in a composer that repeats the office's question and cannot send an empty answer.
- The office's Requests lane draws the same conversation on the request card, so the office decides on the
  answer it asked for.
- One message is attributed per reader: "You" to the technician who wrote it, "Office" to both, and
  "Technician" to the office reading the requester's own answer (`FollowUpConversationSpeaker`, `BR-041`).
- The composer is a confirmation rather than an inline field on a card that opens a Job: the whole card is
  tappable to reach the work, and a technician typing an answer must not be navigated away by a stray tap
  (`BR-012`). The typed text survives a configuration change, as the review confirmation's does.

### D8 — What this record does not change

- `BR-FV-012`'s terminal states, the four status codes and the review routes are unchanged.
- `BR-FV-004`'s "return the request for clarification" is unchanged — it is now reachable *and* answerable,
  which is what option 2 would have removed.
- `BR-FV-011` direct scheduling, `BR-FV-005` approval, `BR-FV-007` Job-open behaviour and `BR-FV-009`
  multiple requests are untouched.
- The request's reviewer fields keep recording the last review decision; an answer is not a review.
- No notification is introduced: the technician opens the list (`BR-029`), and nothing pushes.

## Consequences

- §5.3.1's projection question is untouched by this record: the card still carries no customer, Job number
  or address.
- Whether a message may ever be **edited** or **removed** is not decided here. This record implements
  append-only and neither, so nothing has to be undone if that is decided later (`BR-089` is the precedent
  for a soft removal governed by its own capability).
- Whether the office should read *which* member asked, rather than "the office", is a presentation decision
  rather than a schema one: the message carries its author's membership id, and `authorKind` is derived
  from the request.

