import permissionCodes from '@servora/shared-types/permissions.json';
import { HttpBackend, HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import type {
  AuthDeviceContext,
  AuthMeDto,
  BrowserAuthResponse,
  SignInRequestDto,
  SmsOtpVerifyRequestDto,
} from '@servora/shared-types';
import { BrowserSessionCoordinator } from './browser-session-coordinator';

export type SessionStatus = 'checking' | 'signed-out' | 'authenticated' | 'error';
const headers = { 'X-Servora-Web': '1', 'ngsw-bypass': 'true' };
@Injectable({ providedIn: 'root' })
export class AuthSession {
  private readonly http = new HttpClient(inject(HttpBackend));
  private readonly coordinator = inject(BrowserSessionCoordinator);
  private readonly statusState = signal<SessionStatus>('checking');
  private readonly identityState = signal<AuthMeDto | null>(null);
  private access: string | null = null;
  private generation = 0;
  private readonly boundaryState = signal(0);
  readonly boundary = this.boundaryState.asReadonly();
  private refreshing: Promise<string | null> | null = null;
  readonly status = this.statusState.asReadonly();
  readonly identity = this.identityState.asReadonly();
  readonly authenticated = computed(() => this.status() === 'authenticated');
  readonly logoutFailed = signal(false);
  readonly loggingOut = signal(false);

  constructor() {
    const listener = (event: StorageEvent) => {
      if (event.key === this.coordinator.epochKey || event.key === null) this.clear('signed-out');
    };
    window.addEventListener('storage', listener);
    inject(DestroyRef).onDestroy(() => window.removeEventListener('storage', listener));
  }

  async restore(): Promise<void> {
    this.clear('checking');
    const generation = this.generation;
    try {
      await this.coordinator.run(async () => {
        if (generation !== this.generation) return;
        if (this.coordinator.logoutPending()) {
          await this.revoke();
          return;
        }
        await this.establish(await this.post<BrowserAuthResponse>('refresh', {}), generation);
      });
      if (generation === this.generation && this.status() === 'checking') this.clear('signed-out');
    } catch (error) {
      if (generation !== this.generation) return;
      if (error instanceof HttpErrorResponse && error.status === 401) this.clear('signed-out');
      else this.clear('error');
    }
  }

  signIn(email: string, password: string): Promise<void> {
    return this.authenticate('sign-in', {
      email,
      password,
      device: this.device(),
    } satisfies SignInRequestDto);
  }
  verifySms(phone: string, code: string): Promise<void> {
    return this.authenticate('sms/verify', {
      phone,
      code,
      device: this.device(),
    } satisfies SmsOtpVerifyRequestDto);
  }
  requestSms(phone: string): Promise<void> {
    return this.post<void>('sms/request', { phone });
  }

  private async authenticate(path: string, body: unknown): Promise<void> {
    const requestedGeneration = this.generation;
    const requestedEpoch = this.coordinator.epoch();
    await this.coordinator.run(async () => {
      if (
        requestedGeneration !== this.generation ||
        requestedEpoch !== this.coordinator.epoch() ||
        this.loggingOut()
      ) {
        throw new Error('Session changed');
      }
      // Invalidate the previous browser account before the first network operation.
      this.coordinator.setLogoutPending(true);
      this.coordinator.invalidate();
      this.clear('signed-out');
      const generation = this.generation;
      const epoch = this.coordinator.epoch();
      await this.revoke();
      if (generation !== this.generation || epoch !== this.coordinator.epoch())
        throw new Error('Session changed');
      const response = await this.post<BrowserAuthResponse>(path, body);
      if (generation !== this.generation || epoch !== this.coordinator.epoch())
        throw new Error('Session changed');
      await this.establish(response, generation);
    });
  }

  token(): string | null {
    return this.authenticated() ? this.access : null;
  }
  hasPermission(code: string): boolean {
    return (
      Object.hasOwn(permissionCodes, code) &&
      this.authenticated() &&
      (this.identity()?.permissions.includes(code) ?? false)
    );
  }

  recover(rejectedToken: string): Promise<string | null> {
    if (!this.authenticated()) return Promise.resolve(null);
    if (this.access !== rejectedToken) return Promise.resolve(this.access);
    if (this.refreshing) return this.refreshing;
    const generation = this.generation;
    const epoch = this.coordinator.epoch();
    const refresh = this.coordinator.run(async () => {
      if (generation !== this.generation || epoch !== this.coordinator.epoch()) return null;
      try {
        const response = await this.post<BrowserAuthResponse>('refresh', {});
        if (epoch !== this.coordinator.epoch()) return null;
        await this.establish(response, generation);
        return this.token();
      } catch (error) {
        if (generation === this.generation) {
          if (error instanceof HttpErrorResponse && error.status === 401) this.clear('signed-out');
          else if (
            !(error instanceof HttpErrorResponse) ||
            (error.status !== 0 && error.status < 500)
          )
            this.clear('error');
          // An established in-memory session may keep provisional field work during connection loss.
          // Retrying protected writes still requires the API to accept authentication and exact scope.
        }
        throw error;
      }
    });
    this.refreshing = refresh;
    void refresh
      .finally(() => {
        if (this.refreshing === refresh) this.refreshing = null;
      })
      .catch(() => undefined);
    return refresh;
  }

  async signOut(): Promise<void> {
    this.clear('signed-out');
    this.loggingOut.set(true);
    this.logoutFailed.set(false);
    try {
      this.coordinator.setLogoutPending(true);
      this.coordinator.invalidate();
      await this.coordinator.run(() => this.revoke());
    } catch {
      this.logoutFailed.set(true);
    } finally {
      this.loggingOut.set(false);
    }
  }

  private async revoke(): Promise<void> {
    await this.post<void>('sign-out', {});
    this.coordinator.setLogoutPending(false);
    this.logoutFailed.set(false);
  }
  private async establish(response: BrowserAuthResponse, generation: number): Promise<void> {
    if (generation !== this.generation) return;
    if (
      !response.accessToken ||
      !response.sessionId ||
      !Number.isFinite(Date.parse(response.accessTokenExpiresAt))
    )
      throw new Error('Invalid session response');
    const epoch = this.coordinator.epoch();
    const identity = await firstValueFrom(
      this.http
        .get<AuthMeDto>('/auth/me', {
          headers: { Authorization: `Bearer ${response.accessToken}`, 'ngsw-bypass': 'true' },
          cache: 'no-store',
        })
        .pipe(timeout(15000)),
    );
    if (generation !== this.generation || epoch !== this.coordinator.epoch()) return;
    if (
      !identity.userId ||
      !Array.isArray(identity.permissions) ||
      identity.permissions.some((code) => typeof code !== 'string')
    )
      throw new Error('Invalid identity response');
    this.access = response.accessToken;
    this.identityState.set({ userId: identity.userId, permissions: [...identity.permissions] });
    this.statusState.set('authenticated');
  }
  private clear(status: SessionStatus): void {
    this.generation++;
    this.boundaryState.update((value) => value + 1);
    this.access = null;
    this.identityState.set(null);
    this.statusState.set(status);
  }
  private post<T>(path: string, body: unknown): Promise<T> {
    return firstValueFrom(
      this.http
        .post<T>(`/auth/web/${path}`, body, { headers, cache: 'no-store' })
        .pipe(timeout(15000)),
    );
  }
  private device(): AuthDeviceContext {
    let id = localStorage.getItem('servora.installation-id');
    if (!id) {
      id = crypto.randomUUID();
      localStorage.setItem('servora.installation-id', id);
    }
    return { platform: 'WEB', deviceId: id, deviceName: 'Servora Web', appVersion: '0.0.0' };
  }
}
