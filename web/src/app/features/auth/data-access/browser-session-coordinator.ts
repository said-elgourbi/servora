import { Injectable } from '@angular/core';
const EPOCH = 'servora.session-epoch';
const LOGOUT = 'servora.logout-pending';
@Injectable({ providedIn: 'root' })
export class BrowserSessionCoordinator {
  readonly epochKey = EPOCH;
  epoch(): string {
    return localStorage.getItem(EPOCH) ?? '';
  }
  invalidate(): void {
    localStorage.setItem(EPOCH, crypto.randomUUID());
  }
  logoutPending(): boolean {
    return localStorage.getItem(LOGOUT) === 'true';
  }
  setLogoutPending(pending: boolean): void {
    if (pending) localStorage.setItem(LOGOUT, 'true');
    else localStorage.removeItem(LOGOUT);
  }
  run<T>(work: () => Promise<T>): Promise<T> {
    // Fail closed when shared-cookie rotation cannot be coordinated across browser tabs.
    if (!navigator.locks) return Promise.reject(new Error('Web Locks unavailable'));
    return navigator.locks.request('servora.browser-session', work);
  }
}
