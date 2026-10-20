import type { JobActivityEvent } from '@servora/shared-types';
import { activityText, projectActivity } from './job-activity-model';

function event(
  id: string,
  kind: string,
  overrides: Partial<JobActivityEvent> = {},
): JobActivityEvent {
  return {
    id,
    kind,
    recordedAt: '2026-10-19T12:00:00Z',
    actorName: 'Alex',
    visitSequence: 1,
    fromStatus: null,
    toStatus: null,
    outcomeCode: null,
    outcomeSummary: null,
    body: null,
    photoId: null,
    photoPhase: null,
    audioNoteId: null,
    audioPhase: null,
    audioDurationSeconds: null,
    photoRemovalReason: null,
    audioRemovalReason: null,
    noteEditedAt: null,
    noteRemovedAt: null,
    noteRemovalReason: null,
    quoteFromState: null,
    quoteToState: null,
    quoteNote: null,
    quoteDocumentDisplayName: null,
    quoteDocumentVersion: null,
    ...overrides,
  };
}
const ids = (projection: ReturnType<typeof projectActivity>) =>
  projection.days.flatMap((day) =>
    day.entries.flatMap((entry) => entry.events.map((record) => record.id)),
  );

it('handles an empty or one-update job without inventing system history', () => {
  expect(projectActivity([], 'all', 12)).toMatchObject({ days: [], total: 0, remaining: 0 });
  const note = event('note', 'VISIT_NOTE_ADDED', { body: 'Valve ordered.' });
  expect(projectActivity([note], 'all', 12)).toMatchObject({ updates: 1, system: 0 });
  expect(ids(projectActivity([note], 'updates', 12))).toEqual(['note']);
  expect(ids(projectActivity([note], 'history', 12))).toEqual(['note']);
});
it('keeps scheduling, outcomes, quote decisions and status explanations operational', () => {
  const kinds = [
    'VISIT_NOTE_ADDED',
    'VISIT_OUTCOME_RECORDED',
    'JOB_PHOTO_ADDED',
    'JOB_AUDIO_ADDED',
    'VISIT_SCHEDULED',
    'VISIT_RESCHEDULED',
    'JOB_PROPERTY_CHANGED',
    'JOB_CUSTOMER_CHANGED',
    'JOB_QUOTE_REQUIRED',
    'JOB_QUOTE_SENT',
    'JOB_QUOTE_APPROVED',
    'JOB_QUOTE_REJECTED',
    'JOB_QUOTE_CORRECTED',
    'JOB_QUOTE_DOCUMENT_UPLOADED',
  ];
  const records = kinds.map((kind) => event(kind, kind));
  records.push(
    event('explained', 'JOB_STATUS_CHANGED', { body: 'Customer confirmed cancellation.' }),
  );
  records.push(event('unexplained', 'JOB_STATUS_CHANGED'));
  records.push(event('crew', 'VISIT_TECHNICIAN_ASSIGNED'));
  expect(projectActivity(records, 'all', 100)).toMatchObject({ updates: 15, system: 2, total: 17 });
  expect(ids(projectActivity(records, 'updates', 100))).not.toContain('unexplained');
  expect(ids(projectActivity(records, 'history', 100))).toEqual(records.map((record) => record.id));
});
it('groups an entire crew burst before limiting so notes cannot be starved by churn', () => {
  const crew = Array.from({ length: 60 }, (_, index) =>
    event(
      `crew-${index}`,
      index % 2 ? 'VISIT_TECHNICIAN_ROLE_CHANGED' : 'VISIT_TECHNICIAN_ASSIGNED',
      {
        recordedAt: new Date(Date.parse('2026-10-19T13:00:00Z') - index * 30000).toISOString(),
        actorName: index % 2 ? 'Jo' : 'Alex',
      },
    ),
  );
  const note = event('note', 'VISIT_NOTE_ADDED', { body: 'Parts required.' });
  const feed = projectActivity([...crew, note], 'all', 2);
  expect(feed.remaining).toBe(0);
  expect(feed.days[0]?.entries[0]).toMatchObject({ type: 'summary', category: 'crew' });
  expect(feed.days[0]?.entries[0]?.events).toHaveLength(60);
  expect(ids(feed)).toContain('note');
  expect(ids(projectActivity([...crew, note], 'updates', 1))).toEqual(['note']);
  expect(ids(projectActivity([...crew, note], 'history', 100))).toEqual([
    ...crew.map((record) => record.id),
    'note',
  ]);
});
it('never groups across an operational decision, visit, calendar day or hour-long burst', () => {
  const records = [
    event('a', 'VISIT_TECHNICIAN_ASSIGNED', { recordedAt: '2026-10-19T18:00:00Z' }),
    event('b', 'VISIT_TECHNICIAN_ROLE_CHANGED', {
      recordedAt: '2026-10-19T17:59:00Z',
      visitSequence: 2,
    }),
    event('c', 'VISIT_TECHNICIAN_ROLE_CHANGED', {
      recordedAt: '2026-10-19T16:00:00Z',
      visitSequence: 2,
    }),
    event('decision', 'VISIT_RESCHEDULED', { recordedAt: '2026-10-19T15:59:30Z' }),
    event('d', 'VISIT_TECHNICIAN_REMOVED', {
      recordedAt: '2026-10-19T15:59:00Z',
      visitSequence: 2,
    }),
    event('e', 'VISIT_TECHNICIAN_REMOVED', {
      recordedAt: '2026-10-18T15:59:00Z',
      visitSequence: 2,
    }),
  ];
  const feed = projectActivity(records, 'all', 100);
  expect(feed.days.flatMap((day) => day.entries)).toHaveLength(6);
  expect(feed.days).toHaveLength(2);
});
it('preserves every record, including unknown and removed records, in complete history and progressive views', () => {
  const records = [
    event('photo', 'JOB_PHOTO_ADDED', { photoId: 'photo', body: 'Original caption' }),
    event('removal', 'JOB_PHOTO_REMOVED', { photoId: 'photo', photoRemovalReason: 'Duplicate' }),
    event('note', 'VISIT_NOTE_ADDED', {
      body: 'Retained removed note',
      noteRemovedAt: '2026-10-19T13:00:00Z',
    }),
    event('unknown', 'FUTURE_EVENT'),
    ...Array.from({ length: 40 }, (_, index) => event(`note-${index}`, 'VISIT_NOTE_ADDED')),
  ];
  const complete = ids(projectActivity(records, 'history', 100));
  expect(new Set(complete)).toEqual(new Set(records.map((record) => record.id)));
  expect(complete).toHaveLength(records.length);
  expect(projectActivity(records, 'all', 12).remaining).toBeGreaterThan(0);
  expect(new Set(ids(projectActivity(records, 'all', 100)))).toEqual(new Set(complete));
  expect(ids(projectActivity(records, 'updates', 100))).not.toContain('photo');
  expect(ids(projectActivity(records, 'history', 100))).toContain('photo');
});
it('sorts newest first without changing input, and retains same-instant server order', () => {
  const records = [
    event('old', 'VISIT_NOTE_ADDED'),
    event('new', 'VISIT_NOTE_ADDED', { recordedAt: '2026-10-20T12:00:00Z' }),
    event('same', 'VISIT_NOTE_ADDED'),
  ];
  expect(ids(projectActivity(records, 'all', 100))).toEqual(['new', 'old', 'same']);
  expect(records.map((record) => record.id)).toEqual(['old', 'new', 'same']);
});
it('preserves different authored fields while avoiding identical repeated content', () => {
  expect(
    activityText(
      event('note', 'VISIT_OUTCOME_RECORDED', {
        body: 'Finding',
        outcomeSummary: 'Finding',
        quoteNote: 'Decision',
      }),
    ),
  ).toEqual(['Finding', 'Decision']);
});
