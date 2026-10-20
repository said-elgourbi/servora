import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type {
  CustomerDetails,
  CustomerJob,
  CustomerProperty,
  PropertyDetails,
  PropertyLifecycleRequest,
  SavePropertyRequest,
} from '@servora/shared-types';
import { timeout } from 'rxjs';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';
@Injectable({ providedIn: 'root' })
export class PropertiesApi {
  private readonly http = inject(HttpClient);
  private options() {
    return { context: new HttpContext().set(AUTHENTICATED_REQUEST, true) };
  }
  private path(customerId: string, id?: string) {
    const base = `/customers/${encodeURIComponent(customerId)}/properties`;
    return id ? `${base}/${encodeURIComponent(id)}` : base;
  }
  customer(id: string) {
    return this.http
      .get<CustomerDetails>(`/customers/${encodeURIComponent(id)}`, this.options())
      .pipe(timeout(15000));
  }
  jobs(customerId: string) {
    return this.http
      .get<CustomerJob[]>(`/customers/${encodeURIComponent(customerId)}/jobs`, this.options())
      .pipe(timeout(15000));
  }
  detail(customerId: string, id: string) {
    return this.http
      .get<PropertyDetails>(this.path(customerId, id), this.options())
      .pipe(timeout(15000));
  }
  create(customerId: string, input: SavePropertyRequest) {
    return this.http
      .post<CustomerProperty>(this.path(customerId), input, this.options())
      .pipe(timeout(20000));
  }
  update(customerId: string, id: string, input: SavePropertyRequest) {
    return this.http
      .patch<PropertyDetails>(this.path(customerId, id), input, this.options())
      .pipe(timeout(20000));
  }
  lifecycle(
    customerId: string,
    property: Pick<PropertyDetails, 'id' | 'version'>,
    action: 'archive' | 'restore',
  ) {
    const input: PropertyLifecycleRequest = {
      expectedVersion: property.version,
      clientOperationId: crypto.randomUUID(),
    };
    return this.http
      .post<PropertyDetails>(
        `${this.path(customerId, property.id)}/${action}`,
        input,
        this.options(),
      )
      .pipe(timeout(20000));
  }
  remove(customerId: string, id: string) {
    return this.http.delete<void>(this.path(customerId, id), this.options()).pipe(timeout(20000));
  }
}
