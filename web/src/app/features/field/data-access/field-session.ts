import { BrowserSessionCoordinator } from '../../auth/data-access/browser-session-coordinator';
import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import type { FieldContextDto } from '@servora/shared-types';
import { AuthSession } from '../../auth/data-access/auth-session';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';

export function fieldPartition(scope: FieldContextDto): string {
  return `${scope.userId}/${scope.organizationId}/${scope.membershipId}`;
}
@Injectable({ providedIn: 'root' })
export class FieldSession {
  private readonly coordinator = inject(BrowserSessionCoordinator);
  private readonly auth = inject(AuthSession);
  private readonly transport = inject(HttpClient);
  private readonly verified = signal<{ scope: FieldContextDto; boundary: number } | null>(null);
  readonly issue = signal<string | null>(null);
  readonly context = computed(() => {
    const value = this.verified();
    return value && this.auth.authenticated() && value.boundary === this.auth.boundary()
      ? value.scope
      : null;
  });
  lease(): () => boolean {
    const boundary = this.auth.boundary();
    const partition = this.context();
    return () =>
      !!partition &&
      this.auth.boundary() === boundary &&
      this.context() !== null &&
      fieldPartition(this.context()!) === fieldPartition(partition);
  }
  async verify(): Promise<FieldContextDto> {
    const boundary = this.auth.boundary();
    const token = this.auth.token();
    if (!token) throw new Error('UNAUTHENTICATED');
    try {
      const scope = await firstValueFrom(
        this.transport
          .get<FieldContextDto>('/auth/field-context', {
            context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
            cache: 'no-store',
          })
          .pipe(timeout(15000)),
      );
      if (boundary !== this.auth.boundary() || !this.auth.authenticated())
        throw new Error('SESSION_CHANGED');
      const uuid = /^[0-9a-f-]{36}$/i;
      if (
        scope.userId !== this.auth.identity()?.userId ||
        ![scope.userId, scope.organizationId, scope.membershipId].every((value) => uuid.test(value))
      )
        throw new Error('FIELD_SCOPE_INVALID');
      const previous = this.context();
      if (previous && fieldPartition(previous) !== fieldPartition(scope)) {
        this.invalidate();
        throw new Error('FIELD_SCOPE_CHANGED');
      }
      if (!previous) this.verified.set({ scope, boundary });
      this.issue.set(null);
      return scope;
    } catch (error) {
      // A failed verification never unlocks work. Existing offline access is retained only for transport failures.
      if (boundary === this.auth.boundary()) {
        const status =
          typeof error === 'object' && error !== null && 'status' in error ? error.status : null;
        if (status !== 0 && !(typeof status === 'number' && status >= 500)) this.invalidate();
        this.issue.set('fieldContextUnavailable');
      }
      throw error;
    }
  }
  invalidate(): void {
    this.verified.set(null);
    this.coordinator.invalidate();
  }
  options(scope: FieldContextDto) {
    return {
      context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
      headers: {
        'X-Servora-Field-User': scope.userId,
        'X-Servora-Field-Organization': scope.organizationId,
        'X-Servora-Field-Membership': scope.membershipId,
      },
    };
  }
}
