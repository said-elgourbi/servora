import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type { CreateCustomerRequest, CustomerContact, CustomerDetails, CustomerJob, CustomerJobFilter, CustomerListSummary, CustomerProperty, CustomerStatusFilter, PropertyDetails, PropertyLifecycleRequest, PropertyStatusFilter, SaveContactRequest, SaveCustomerRequest, SavePropertyRequest } from '@servora/shared-types';
import { timeout } from 'rxjs';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';

@Injectable({ providedIn: 'root' })
export class CustomersApi {
  private readonly http = inject(HttpClient);
  list(status: CustomerStatusFilter = 'ALL', jobs: CustomerJobFilter = 'ALL') {
    return this.http.get<CustomerListSummary[]>('/customers', {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
      params: { status, jobs },
    }).pipe(timeout(15000));
  }
  customer(id: string) {
    return this.http.get<CustomerDetails>(`/customers/${encodeURIComponent(id)}`, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
    }).pipe(timeout(15000));
  }
  jobs(id: string) {
    return this.http.get<CustomerJob[]>(`/customers/${encodeURIComponent(id)}/jobs`, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
    }).pipe(timeout(15000));
  }
  properties(id: string, status: PropertyStatusFilter = 'ACTIVE') {
    return this.http.get<CustomerProperty[]>(`/customers/${encodeURIComponent(id)}/properties`, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true), params: { status },
    }).pipe(timeout(15000));
  }
  property(id: string, propertyId: string) {
    return this.http.get<PropertyDetails>(`/customers/${encodeURIComponent(id)}/properties/${encodeURIComponent(propertyId)}`, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
    }).pipe(timeout(15000));
  }
  create(body: CreateCustomerRequest) {
    return this.http.post<CustomerDetails>('/customers', body, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
    }).pipe(timeout(15000));
  }
  update(id: string, body: SaveCustomerRequest) {
    return this.http.patch<CustomerDetails>(`/customers/${encodeURIComponent(id)}`, body, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
    }).pipe(timeout(15000));
  }
  archive(id: string, reason?: string) {
    return this.http.post(`/customers/${encodeURIComponent(id)}/archive`, { reason: reason || null }, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
    }).pipe(timeout(15000));
  }
  addContact(id: string, body: SaveContactRequest) {
    return this.http.post<CustomerContact>(`/customers/${encodeURIComponent(id)}/contacts`, body, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
    }).pipe(timeout(15000));
  }
  updateContact(id: string, contactId: string, body: SaveContactRequest & { expectedVersion: number }) {
    return this.http.patch<CustomerContact>(`/customers/${encodeURIComponent(id)}/contacts/${encodeURIComponent(contactId)}`, body, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
    }).pipe(timeout(15000));
  }
  removeContact(id: string, contactId: string, expectedVersion: number) {
    return this.http.delete<void>(`/customers/${encodeURIComponent(id)}/contacts/${encodeURIComponent(contactId)}`, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true), body: { expectedVersion },
    }).pipe(timeout(15000));
  }
  createProperty(id: string, body: SavePropertyRequest) {
    return this.http.post<CustomerProperty>(`/customers/${encodeURIComponent(id)}/properties`, body, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
    }).pipe(timeout(15000));
  }
  updateProperty(id: string, propertyId: string, body: SavePropertyRequest) {
    return this.http.patch<PropertyDetails>(`/customers/${encodeURIComponent(id)}/properties/${encodeURIComponent(propertyId)}`, body, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
    }).pipe(timeout(15000));
  }
  archiveProperty(id: string, propertyId: string, body: PropertyLifecycleRequest) {
    return this.http.post<PropertyDetails>(`/customers/${encodeURIComponent(id)}/properties/${encodeURIComponent(propertyId)}/archive`, body, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
    }).pipe(timeout(15000));
  }
  restoreProperty(id: string, propertyId: string, body: PropertyLifecycleRequest) {
    return this.http.post<PropertyDetails>(`/customers/${encodeURIComponent(id)}/properties/${encodeURIComponent(propertyId)}/restore`, body, {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
    }).pipe(timeout(15000));
  }
}
