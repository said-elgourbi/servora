import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type {
  CustomerDetails,
  CustomerJob,
  CustomerJobFilter,
  CustomerProperty,
  PropertyStatusFilter,
  CustomerRecord,
  CustomerStatusFilter,
  CustomerListSummary,
  CustomerWriteResult,
  SaveContactRequest,
  CustomerContact,
  SaveCustomerRequest,
} from '@servora/shared-types';
import { timeout } from 'rxjs';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';

@Injectable({ providedIn: 'root' })
export class CustomersApi {
  private readonly http = inject(HttpClient);
  private options() {
    return { context: new HttpContext().set(AUTHENTICATED_REQUEST, true) };
  }
  private path(id: string) {
    return `/customers/${encodeURIComponent(id)}`;
  }
  list(status: CustomerStatusFilter, jobs: CustomerJobFilter) {
    return this.http
      .get<CustomerListSummary[]>('/customers', { ...this.options(), params: { status, jobs } })
      .pipe(timeout(15000));
  }
  detail(id: string) {
    return this.http.get<CustomerDetails>(this.path(id), this.options()).pipe(timeout(15000));
  }
  properties(id: string, status: PropertyStatusFilter = 'ACTIVE') {
    return this.http
      .get<CustomerProperty[]>(`${this.path(id)}/properties`, {
        ...this.options(),
        params: { status },
      })
      .pipe(timeout(15000));
  }
  jobs(id: string) {
    return this.http
      .get<CustomerJob[]>(`${this.path(id)}/jobs`, this.options())
      .pipe(timeout(15000));
  }
  save(input: SaveCustomerRequest, id?: string) {
    return (
      id
        ? this.http.patch<CustomerWriteResult>(this.path(id), input, this.options())
        : this.http.post<CustomerWriteResult>('/customers', input, this.options())
    ).pipe(timeout(20000));
  }
  archive(id: string) {
    return this.http
      .post<CustomerRecord>(`${this.path(id)}/archive`, {}, this.options())
      .pipe(timeout(20000));
  }
  saveContact(
    id: string,
    input: SaveContactRequest,
    contact?: Pick<CustomerContact, 'id' | 'version'>,
  ) {
    const path = `${this.path(id)}/contacts`;
    return (
      contact
        ? this.http.patch<CustomerContact>(
            `${path}/${encodeURIComponent(contact.id)}`,
            { ...input, expectedVersion: contact.version },
            this.options(),
          )
        : this.http.post<CustomerContact>(path, input, this.options())
    ).pipe(timeout(20000));
  }
  removeContact(id: string, contact: Pick<CustomerContact, 'id' | 'version'>) {
    return this.http
      .delete<void>(`${this.path(id)}/contacts/${encodeURIComponent(contact.id)}`, {
        ...this.options(),
        body: { expectedVersion: contact.version },
      })
      .pipe(timeout(20000));
  }
}
