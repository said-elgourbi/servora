import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { AuthSession } from '../../auth/data-access/auth-session';
/** Home is currently the only API projection that supplies a display name. */
@Injectable({ providedIn: 'root' })
export class WorkspaceIdentity {
  private readonly session = inject(AuthSession);
  private readonly profile = signal<{ userId: string; name: string } | null>(null);
  readonly displayName = computed(() => {
    const profile = this.profile();
    return this.session.authenticated() && profile?.userId === this.session.identity()?.userId
      ? (profile?.name ?? null)
      : null;
  });
  constructor() {
    effect(() => {
      if (!this.session.authenticated()) this.profile.set(null);
    });
  }
  update(name: string | null): void {
    const userId = this.session.identity()?.userId;
    this.profile.set(userId && name ? { userId, name } : null);
  }
}
