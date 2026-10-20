import type { JobActivityEvent } from '@servora/shared-types';

export type ActivityView = 'all' | 'updates' | 'history';
export type SystemCategory = 'crew' | 'status' | 'removal' | 'other';
export interface ActivityEntry {
  id: string;
  type: 'update' | 'summary' | 'history';
  category: SystemCategory;
  events: [JobActivityEvent, ...JobActivityEvent[]];
}
export interface ActivityDay {
  key: string;
  date: string;
  entries: ActivityEntry[];
}
const operationalKinds = new Set([
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
]);
const crewKinds = new Set([
  'VISIT_TECHNICIAN_ASSIGNED',
  'VISIT_TECHNICIAN_REMOVED',
  'VISIT_TECHNICIAN_ROLE_CHANGED',
]);
function category(event: JobActivityEvent): SystemCategory {
  if (crewKinds.has(event.kind)) return 'crew';
  if (event.kind === 'JOB_STATUS_CHANGED' || event.kind === 'VISIT_STATUS_CHANGED') return 'status';
  if (event.kind.endsWith('_REMOVED') || event.noteRemovedAt || event.photoId || event.audioNoteId)
    return 'removal';
  return 'other';
}
function dayKey(timestamp: string): string {
  const date = new Date(timestamp);
  // Use the same browser-local day as DatePipe, rather than slicing the UTC date.
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}
export function activityText(event: JobActivityEvent): string[] {
  return [
    ...new Set(
      [event.body, event.outcomeSummary, event.quoteNote].filter(
        (text): text is string => !!text?.trim(),
      ),
    ),
  ];
}

/** Presentation only: Updates is a subset; History retains every record. No inferred job outcomes. */
export function projectActivity(
  events: readonly JobActivityEvent[],
  view: ActivityView,
  limit: number,
) {
  const removedPhotos = new Set(
    events.filter((event) => event.kind === 'JOB_PHOTO_REMOVED').map((event) => event.photoId),
  );
  const removedAudio = new Set(
    events.filter((event) => event.kind === 'JOB_AUDIO_REMOVED').map((event) => event.audioNoteId),
  );
  const operational = (event: JobActivityEvent): boolean => {
    if (
      event.noteRemovedAt ||
      (event.photoId && removedPhotos.has(event.photoId)) ||
      (event.audioNoteId && removedAudio.has(event.audioNoteId))
    )
      return false;
    return (
      operationalKinds.has(event.kind) ||
      // An explanation attached to a status change is a meaningful human update.
      ((event.kind === 'JOB_STATUS_CHANGED' || event.kind === 'VISIT_STATUS_CHANGED') &&
        activityText(event).length > 0)
    );
  };
  const ordered = [...events].sort((a, b) => Date.parse(b.recordedAt) - Date.parse(a.recordedAt));
  const updates = ordered.filter(operational).length;
  const selected = ordered.filter((event) => view !== 'updates' || operational(event));
  const entries: ActivityEntry[] = [];
  for (const event of selected) {
    const type = view === 'history' ? 'history' : operational(event) ? 'update' : 'summary';
    const family = category(event);
    const previous = entries.at(-1);
    // Group only contiguous, related system records. Never cross an update, visit, day, or hour-long burst.
    if (
      type === 'summary' &&
      previous?.type === 'summary' &&
      previous.category === family &&
      previous.events[0].visitSequence === event.visitSequence &&
      dayKey(previous.events[0].recordedAt) === dayKey(event.recordedAt) &&
      Date.parse(previous.events[0].recordedAt) - Date.parse(event.recordedAt) <= 60 * 60 * 1000
    ) {
      previous.events.push(event);
    } else entries.push({ id: event.id, type, category: family, events: [event] });
  }
  const days: ActivityDay[] = [];
  // Apply the display limit after grouping, so crew churn cannot crowd substantive updates out.
  for (const entry of entries.slice(0, limit)) {
    const date = entry.events[0].recordedAt,
      key = dayKey(date);
    let day = days.at(-1);
    if (!day || day.key !== key) {
      day = { key, date, entries: [] };
      days.push(day);
    }
    day.entries.push(entry);
  }
  return {
    days,
    updates,
    system: events.length - updates,
    total: events.length,
    remaining: Math.max(0, entries.length - limit),
  };
}
