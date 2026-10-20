import type { Page } from '@playwright/test';
import type { JobActivityEvent, OfficeJobDetails } from '@servora/shared-types';
import { job } from './workspace';
import { mockProperties, propertyPermissions } from './properties';
export const jobPermissions = [
  ...propertyPermissions,
  'JOB_CREATE',
  'JOB_UPDATE',
  'evidence.view',
  'evidence.photo.remove',
  'evidence.audio.remove',
];
export const photoEvent: JobActivityEvent = {
  id: 'event-photo',
  kind: 'JOB_PHOTO_ADDED',
  recordedAt: '2026-10-19T12:00:00Z',
  actorName: 'Alex Tremblay',
  visitSequence: 1,
  fromStatus: null,
  toStatus: null,
  outcomeCode: null,
  outcomeSummary: null,
  body: 'Furnace before repair',
  photoId: 'photo-one',
  photoPhase: 'BEFORE',
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
};
export async function mockJobs(
  page: Page,
  permissions = jobPermissions,
  initial: Partial<OfficeJobDetails> = {},
) {
  await mockProperties(page, permissions);
  let current: OfficeJobDetails = { ...job, version: 6, ...initial };
  await page.route('**/technicians', (route) =>
    route.fulfill({
      json: [
        { membershipId: 'member-one', name: 'Alex Tremblay' },
        { membershipId: 'member-two', name: 'Jo Tremblay' },
      ],
    }),
  );
  let removed = false;
  const writes: { path: string; body: Record<string, unknown> }[] = [];
  const reads: string[] = [];
  await page.route(/^http:\/\/[^/]+\/jobs(?:\/|\?|$)/, async (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname;
    if (request.method() === 'GET') reads.push(path);
    else writes.push({ path, body: request.postDataJSON() as Record<string, unknown> });
    if (path.endsWith('/activity')) {
      const audit = new URL(request.url()).searchParams.has('includeRemovedEvidence');
      return route.fulfill({
        json: {
          jobId: current.id,
          events: !removed
            ? [photoEvent]
            : audit
              ? [
                  {
                    ...photoEvent,
                    id: 'removed',
                    kind: 'JOB_PHOTO_REMOVED',
                    photoRemovalReason: 'Duplicate photo',
                  },
                  photoEvent,
                ]
              : [],
        },
      });
    }
    if (path.endsWith('/content'))
      return route.fulfill({
        contentType: 'image/png',
        body: Buffer.from(
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jO1kAAAAASUVORK5CYII=',
          'base64',
        ),
      });
    if (path.endsWith('/removal')) {
      removed = true;
      return route.fulfill({ status: 201, json: { jobId: current.id, events: [] } });
    }
    if (path === '/jobs' && request.method() === 'POST') {
      current = {
        ...current,
        ...request.postDataJSON(),
        id: 'created-job',
        jobNumber: 1044,
        status: 'NEW',
        visits: [],
        selectedVisit: null,
        allowedStatusTransitions: ['ACTIVE', 'COMPLETED', 'CANCELED'],
        version: 1,
      };
      return route.fulfill({ status: 201, json: current });
    }
    if (path.endsWith('/visits') && request.method() === 'POST') {
      const body = request.postDataJSON();
      const nextVisit = {
        id: 'visit-two',
        sequence: current.visits.length + 1,
        status: 'SCHEDULED',
        purposeCode: body.purposeCode,
        outcomeCode: null,
        scheduledStart: body.scheduledStart,
        scheduledEnd: body.scheduledEnd,
        technicians: body.technicians.map((member: { membershipId: string; roleCode: string }) => ({
          ...member,
          name: member.membershipId === 'member-one' ? 'Alex Tremblay' : 'Jo Tremblay',
        })),
      };
      current = {
        ...current,
        canScheduleVisit: false,
        status: 'ACTIVE',
        visits: [...current.visits, nextVisit],
        selectedVisit: { id: nextVisit.id },
      };
      return route.fulfill({ status: 201, json: current });
    }
    if (path.endsWith('/status')) {
      const body = request.postDataJSON() as { status: string };
      current = {
        ...current,
        status: body.status,
        canScheduleVisit:
          body.status === 'ACTIVE' &&
          permissions.includes('visits.create_schedule') &&
          current.visits.every((visit) => ['COMPLETED', 'CANCELED'].includes(visit.status)),
        version: current.version + 1,
        allowedStatusTransitions: ['COMPLETED', 'CANCELED'].includes(body.status)
          ? ['ACTIVE']
          : ['COMPLETED', 'CANCELED'],
      };
      return route.fulfill({ json: current });
    }
    return route.fulfill({ json: current });
  });
  return { writes, reads };
}
