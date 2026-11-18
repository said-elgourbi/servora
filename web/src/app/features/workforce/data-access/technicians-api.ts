import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type { ManagedTechnician, TechnicianManagement, UpdateTechnicianRequest } from '@servora/shared-types';
import { timeout } from 'rxjs';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';

@Injectable({ providedIn: 'root' })
export class TechniciansApi {
  private readonly http = inject(HttpClient);
  private options() { return { context: new HttpContext().set(AUTHENTICATED_REQUEST, true) }; }
  list() { return this.http.get<TechnicianManagement>('/technicians/management', this.options()).pipe(timeout(15000)); }
  update(id: string, input: UpdateTechnicianRequest) {
    return this.http.patch<ManagedTechnician>(`/technicians/${encodeURIComponent(id)}`, input, this.options()).pipe(timeout(20000));
  }
}
