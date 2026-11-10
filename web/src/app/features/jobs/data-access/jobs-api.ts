import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type {
  AssignableTechnician,
  ScheduleVisitRequest,
  CreateJobRequest,
  ChangeJobStatusRequest,
  OfficeJobDetails,
  EvidencePhase,
  JobScheduleSearchOption,
  JobActivity,
  RescheduleVisitRequest,
  AssignVisitTechniciansRequest,
  AdHocWorkReport,
  AdHocReviewCustomerOption,
  AdHocReviewPropertyOption,
  AdHocReviewJobOption,
  FieldFollowUpDto,
} from '@servora/shared-types';
import type { FieldContextDto } from '@servora/shared-types';
import { timeout } from 'rxjs';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';
import { FieldSession } from '../../field/data-access/field-session';
@Injectable({ providedIn: 'root' })
export class JobsApi {
  private readonly http = inject(HttpClient);
  private readonly fieldSession = inject(FieldSession);
  private options() {
    return { context: new HttpContext().set(AUTHENTICATED_REQUEST, true) };
  }
  private scopedOptions(scope?: FieldContextDto) {
    return scope ? this.fieldSession.options(scope) : this.options();
  }
  private path(id: string) {
    return `/jobs/${encodeURIComponent(id)}`;
  }
  adHocReports() {
    return this.http.get<AdHocWorkReport[]>('/jobs/ad-hoc-work-reports', this.options()).pipe(timeout(15000));
  }
  adHocCustomers(query: string) {
    return this.http.get<AdHocReviewCustomerOption[]>('/jobs/ad-hoc-work-reports/review-customer-options', {
      ...this.options(), params: { q: query },
    }).pipe(timeout(15000));
  }
  adHocProperties(customerId: string) {
    return this.http.get<AdHocReviewPropertyOption[]>('/jobs/ad-hoc-work-reports/review-property-options', {
      ...this.options(), params: { customerId },
    }).pipe(timeout(15000));
  }
  adHocJobs(customerId: string | null, query: string) {
    return this.http.get<AdHocReviewJobOption[]>('/jobs/ad-hoc-work-reports/review-job-options', {
      ...this.options(), params: customerId ? { customerId } : { q: query },
    }).pipe(timeout(15000));
  }
  resolveAdHocLocation(id: string, input: Record<string, unknown>) {
    return this.http.post<AdHocWorkReport>(`/jobs/ad-hoc-work-reports/${encodeURIComponent(id)}/resolve-location`, input, this.options()).pipe(timeout(20000));
  }
  linkAdHocReport(id: string, input: Record<string, unknown>) {
    return this.http.post<OfficeJobDetails>(`/jobs/ad-hoc-work-reports/${encodeURIComponent(id)}/link`, input, this.options()).pipe(timeout(20000));
  }
  convertAdHocReport(id: string, input: Record<string, unknown>) {
    return this.http.post<OfficeJobDetails>(`/jobs/ad-hoc-work-reports/${encodeURIComponent(id)}/convert`, input, this.options()).pipe(timeout(20000));
  }
  rejectAdHocReport(id: string, input: Record<string, unknown>) {
    return this.http.post<AdHocWorkReport>(`/jobs/ad-hoc-work-reports/${encodeURIComponent(id)}/reject`, input, this.options()).pipe(timeout(20000));
  }
  detail(id: string, visitId?: string | null) {
    return this.http.get<OfficeJobDetails>(this.path(id), {
      ...this.options(),
      params: visitId ? { visitId } : {},
    }).pipe(timeout(15000));
  }
  searchForScheduling(query: string) {
    return this.http
      .get<JobScheduleSearchOption[]>('/jobs/search', {
        ...this.options(),
        params: { query },
      })
      .pipe(timeout(15000));
  }
  create(input: CreateJobRequest) {
    return this.http.post<OfficeJobDetails>('/jobs', input, this.options()).pipe(timeout(20000));
  }
  status(id: string, input: ChangeJobStatusRequest) {
    return this.http
      .patch<OfficeJobDetails>(`${this.path(id)}/status`, input, this.options())
      .pipe(timeout(20000));
  }
  visitStatus(id: string, visitId: string, status: string, expectedVersion: number) {
    return this.http.patch<OfficeJobDetails>(
      `${this.path(id)}/visits/${encodeURIComponent(visitId)}/status`,
      { status, outcomeCode: null, outcomeSummary: null, clientOperationId: null, capturedAt: null, expectedVersion, confirmConflicts: false },
      this.options(),
    ).pipe(timeout(20000));
  }
  completeVisit(
    id: string,
    visitId: string,
    input: { outcomeCode: string; outcomeSummary: string; expectedVersion: number },
  ) {
    return this.http.post<OfficeJobDetails>(
      `${this.path(id)}/visits/${encodeURIComponent(visitId)}/completion`,
      { ...input, clientOperationId: crypto.randomUUID(), capturedAt: new Date().toISOString() },
      this.options(),
    ).pipe(timeout(20000));
  }
  correctVisitOutcome(
    id: string,
    visitId: string,
    input: { outcomeCode: string; outcomeSummary: string; reason: string | null; expectedVersion: number },
  ) {
    return this.http.patch<OfficeJobDetails>(
      `${this.path(id)}/visits/${encodeURIComponent(visitId)}/outcome`,
      { ...input, clientOperationId: crypto.randomUUID(), capturedAt: new Date().toISOString() },
      this.options(),
    ).pipe(timeout(20000));
  }
  technicians() {
    return this.http
      .get<AssignableTechnician[]>('/technicians', this.options())
      .pipe(timeout(15000));
  }
  schedule(id: string, input: ScheduleVisitRequest) {
    return this.http
      .post<OfficeJobDetails>(`${this.path(id)}/visits`, input, this.options())
      .pipe(timeout(20000));
  }
  reschedule(id: string, visitId: string, input: RescheduleVisitRequest) {
    return this.http
      .patch<OfficeJobDetails>(
        `${this.path(id)}/visits/${encodeURIComponent(visitId)}/schedule`,
        input,
        this.options(),
      )
      .pipe(timeout(20000));
  }
  assignTechnicians(id: string, visitId: string, input: AssignVisitTechniciansRequest) {
    return this.http
      .put<OfficeJobDetails>(
        `${this.path(id)}/visits/${encodeURIComponent(visitId)}/technicians`,
        input,
        this.options(),
      )
      .pipe(timeout(20000));
  }
  requestFollowUp(id: string, input: {
    sourceVisitId: string;
    proposedStart: string;
    proposedEnd: string;
    reason: string;
    sameTechnicianPreferred: boolean;
  }, scope?: FieldContextDto) {
    return this.http.post<FieldFollowUpDto>(`${this.path(id)}/visit-requests`, input, this.scopedOptions(scope)).pipe(timeout(20000));
  }
  activity(id: string, audit = false) {
    return this.http
      .get<JobActivity>(`${this.path(id)}/activity`, {
        ...this.options(),
        params: audit ? { includeRemovedEvidence: 'true' } : {},
      })
      .pipe(timeout(15000));
  }
  activityHistory(id: string, kind: string, activityId: string) {
    return this.http.get<{ id: string; previousValue: Record<string, unknown>; recordedAt: string }[]>(
      `${this.path(id)}/activity/${encodeURIComponent(kind)}/${encodeURIComponent(activityId)}/history`,
      this.options(),
    ).pipe(timeout(15000));
  }
  editJobNote(id: string, noteId: string, body: string) {
    return this.http.patch<JobActivity>(`${this.path(id)}/notes/${encodeURIComponent(noteId)}`, { body }, this.options()).pipe(timeout(20000));
  }
  editVisitNote(id: string, noteId: string, body: string) {
    return this.http.patch<JobActivity>(`${this.path(id)}/visits/notes/${encodeURIComponent(noteId)}`, { body }, this.options()).pipe(timeout(20000));
  }
  removeNote(id: string, noteId: string, visitNote: boolean) {
    const path = visitNote ? `${this.path(id)}/visits/notes/${encodeURIComponent(noteId)}/removal` : `${this.path(id)}/notes/${encodeURIComponent(noteId)}/removal`;
    return this.http.post<JobActivity>(path, visitNote ? { reason: 'Deleted by the author or manager' } : {}, this.options()).pipe(timeout(20000));
  }
  editPhoto(id: string, photoId: string, file: File | null, note: string, phase: EvidencePhase) {
    const form = new FormData(); form.append('note', note); form.append('phase', phase); if (file) form.append('file', file);
    return this.http.patch<JobActivity>(`${this.path(id)}/photos/${encodeURIComponent(photoId)}`, form, this.options()).pipe(timeout(30000));
  }
  editAudio(id: string, audioId: string, file: File | null, note: string, phase: EvidencePhase) {
    const form = new FormData(); form.append('note', note); form.append('phase', phase); if (file) form.append('file', file);
    return this.http.patch<JobActivity>(`${this.path(id)}/audio-notes/${encodeURIComponent(audioId)}`, form, this.options()).pipe(timeout(30000));
  }
  editDocument(id: string, documentId: string, file: File | null, displayName: string) {
    const form = new FormData(); form.append('displayName', displayName); if (file) form.append('file', file);
    return this.http.patch<JobActivity>(`${this.path(id)}/documents/${encodeURIComponent(documentId)}`, form, this.options()).pipe(timeout(30000));
  }
  removeDocument(id: string, documentId: string) {
    return this.http.post<JobActivity>(`${this.path(id)}/documents/${encodeURIComponent(documentId)}/removal`, {}, this.options()).pipe(timeout(20000));
  }
  addJobNote(id: string, body: string) {
    return this.http
      .post<JobActivity>(`${this.path(id)}/notes`, { body, clientOperationId: crypto.randomUUID() }, this.options())
      .pipe(timeout(20000));
  }
  addVisitNote(id: string, visitId: string, body: string, scope?: FieldContextDto) {
    return this.http
      .post<JobActivity>(`${this.path(id)}/visits/${encodeURIComponent(visitId)}/notes`, {
        body,
        clientOperationId: crypto.randomUUID(),
      }, this.scopedOptions(scope))
      .pipe(timeout(20000));
  }
  addPhoto(
    id: string,
    file: File,
    note = '',
    visitId: string | null = null,
    phase: EvidencePhase = 'DURING_WORK',
    operationId: string = crypto.randomUUID(),
    scope?: FieldContextDto,
  ) {
    const form = new FormData();
    form.append('file', file);
    form.append('clientOperationId', operationId);
    form.append('phase', phase);
    if (scope) form.append('capturedAt', new Date().toISOString());
    if (visitId) form.append('visitId', visitId);
    if (note.trim()) form.append('note', note.trim());
    return this.http.post<JobActivity>(`${this.path(id)}/photos`, form, this.scopedOptions(scope)).pipe(timeout(30000));
  }
  addAudio(
    id: string,
    file: File,
    note = '',
    visitId: string | null = null,
    phase: EvidencePhase = 'DURING_WORK',
    operationId: string = crypto.randomUUID(),
    scope?: FieldContextDto,
  ) {
    const form = new FormData();
    form.append('file', file);
    form.append('clientOperationId', operationId);
    form.append('phase', phase);
    if (scope) form.append('capturedAt', new Date().toISOString());
    if (visitId) form.append('visitId', visitId);
    if (note.trim()) form.append('note', note.trim());
    return this.http.post<JobActivity>(`${this.path(id)}/audio-notes`, form, this.scopedOptions(scope)).pipe(timeout(30000));
  }
  addDocument(id: string, file: File, visitId: string | null = null, operationId: string = crypto.randomUUID(), scope?: FieldContextDto) {
    const form = new FormData();
    form.append('file', file);
    form.append('documentType', 'ATTACHMENT');
    form.append('displayName', file.name);
    form.append('clientOperationId', operationId);
    if (visitId) form.append('visitId', visitId);
    return this.http.post<unknown>(`${this.path(id)}/documents`, form, this.scopedOptions(scope)).pipe(timeout(30000));
  }
  documentContent(id: string, documentId: string) {
    return this.http.get(`${this.path(id)}/documents/${encodeURIComponent(documentId)}/content`, {
      ...this.options(), responseType: 'blob',
    }).pipe(timeout(20000));
  }
  content(id: string, mediaId: string, audio: boolean) {
    return this.http
      .get(
        `${this.path(id)}/${audio ? 'audio-notes' : 'photos'}/${encodeURIComponent(mediaId)}/content`,
        { ...this.options(), responseType: 'blob' },
      )
      .pipe(timeout(20000));
  }
  remove(id: string, mediaId: string, audio: boolean, reason: string) {
    return this.http
      .post<JobActivity>(
        `${this.path(id)}/${audio ? 'audio-notes' : 'photos'}/${encodeURIComponent(mediaId)}/removal`,
        { reason },
        this.options(),
      )
      .pipe(timeout(20000));
  }
}
