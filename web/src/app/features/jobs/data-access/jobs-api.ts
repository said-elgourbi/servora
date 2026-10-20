import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type {
  AssignableTechnician,
  ScheduleVisitRequest,
  CreateJobRequest,
  ChangeJobStatusRequest,
  OfficeJobDetails,
  JobActivity,
  RescheduleVisitRequest,
  AssignVisitTechniciansRequest,
} from '@servora/shared-types';
import { timeout } from 'rxjs';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';
@Injectable({ providedIn: 'root' })
export class JobsApi {
  private readonly http = inject(HttpClient);
  private options() {
    return { context: new HttpContext().set(AUTHENTICATED_REQUEST, true) };
  }
  private path(id: string) {
    return `/jobs/${encodeURIComponent(id)}`;
  }
  detail(id: string) {
    return this.http.get<OfficeJobDetails>(this.path(id), this.options()).pipe(timeout(15000));
  }
  create(input: CreateJobRequest) {
    return this.http.post<OfficeJobDetails>('/jobs', input, this.options()).pipe(timeout(20000));
  }
  status(id: string, input: ChangeJobStatusRequest) {
    return this.http
      .patch<OfficeJobDetails>(`${this.path(id)}/status`, input, this.options())
      .pipe(timeout(20000));
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
  activity(id: string, audit = false) {
    return this.http
      .get<JobActivity>(`${this.path(id)}/activity`, {
        ...this.options(),
        params: audit ? { includeRemovedEvidence: 'true' } : {},
      })
      .pipe(timeout(15000));
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
