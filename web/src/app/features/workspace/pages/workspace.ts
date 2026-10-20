import { FieldData } from '../../field/data-access/field-data';
import { FieldSession } from '../../field/data-access/field-session';
import { from, switchMap, catchError } from 'rxjs';
import { PageTitle } from '../../../shared/ui/page-title';
import { WorkspaceIdentity } from '../data-access/workspace-identity';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import type { ManagerHomeResponse, TechnicianHomeResponse } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { AuthSession } from '../../auth/data-access/auth-session';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';
import { homeView } from '../data-access/home-access';
import { VisitCard } from '../ui/visit-card';
import { Icon } from '../../../shared/ui/icon';
import { WorkLabel } from '../../../shared/ui/work-label';
@Component({
  selector: 'app-workspace',
  imports: [
    PageTitle,
    RouterLink,
    DatePipe,
    NgTemplateOutlet,
    MatButtonModule,
    VisitCard,
    WorkLabel,
    Icon,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './workspace.html',
  styleUrl: './workspace.scss',
})
export class Workspace {
  protected readonly i18n = inject(I18n);
  private readonly identity = inject(WorkspaceIdentity);
  private readonly session = inject(AuthSession);
  private readonly fieldData = inject(FieldData);
  protected readonly fieldSession = inject(FieldSession);
  protected readonly snapshotTime = signal<number | null>(null);
  private readonly http = inject(HttpClient);
  private readonly query = toSignal(inject(ActivatedRoute).queryParamMap);
  private readonly revision = signal(0);
  protected readonly office = computed(() => this.session.hasPermission('customers.view'));
  protected readonly field = computed(() => this.session.hasPermission('VISIT_VIEW_ASSIGNED'));
  protected readonly view = computed(() =>
    homeView(this.office(), this.field(), this.query()?.get('view') ?? null),
  );
  protected readonly data = signal<ManagerHomeResponse | TechnicianHomeResponse | null>(null);
  protected readonly manager = computed(() => {
    const data = this.data();
    return data && 'today' in data ? data : null;
  });
  protected readonly technician = computed(() => {
    const data = this.data();
    return data && 'nextVisit' in data ? data : null;
  });
  protected readonly loading = signal(false);
  protected readonly error = signal<
    'forbiddenDescription' | 'connectionError' | 'unexpectedError' | null
  >(null);
  constructor() {
    effect((cleanup) => {
      const view = this.view();
      this.revision();
      this.data.set(null);
      this.error.set(null);
      this.loading.set(false);
      if (!view) return;
      this.loading.set(true);
      const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
      this.snapshotTime.set(null);
      const onlineRead = () =>
        this.http.get<ManagerHomeResponse | TechnicianHomeResponse>(
          `/home/${view === 'office' ? 'manager' : 'technician'}`,
          { params: { timeZone }, context: new HttpContext().set(AUTHENTICATED_REQUEST, true) },
        );
      const source =
        view === 'field'
          ? from(untracked(() => this.fieldData.home(timeZone))).pipe(
              switchMap((result) => {
                this.snapshotTime.set(result.stale ? result.fetchedAt : null);
                return from([result.data]);
              }),
              catchError((error) =>
                this.fieldSession.issue() ? onlineRead() : from(Promise.reject(error)),
              ),
            )
          : onlineRead();
      const request = source.subscribe({
        next: (data) => {
          this.data.set(data);
          this.identity.update(data.viewer.displayName);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.error.set(
            error instanceof HttpErrorResponse && error.status === 403
              ? 'forbiddenDescription'
              : error instanceof HttpErrorResponse && error.status === 0
                ? 'connectionError'
                : 'unexpectedError',
          );
        },
      });
      cleanup(() => request.unsubscribe());
    });
  }
  protected dayLabel(start: string, timeZone: string): string {
    return new Intl.DateTimeFormat(this.i18n.language(), { dateStyle: 'full', timeZone }).format(
      new Date(start),
    );
  }
  protected refresh(): void {
    this.revision.update((value) => value + 1);
  }
}
