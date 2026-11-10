import { DatePipe } from '@angular/common';
import { HttpClient, HttpContext } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import type { ManagerHomeResponse, TechnicianHomeResponse } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';
import { AuthSession } from '../../auth/data-access/auth-session';
import { WorkspaceIdentity } from '../../workspace/data-access/workspace-identity';
import { PageTitle } from '../../../shared/ui/page-title';
import { Icon } from '../../../shared/ui/icon';
import { WorkLabel } from '../../../shared/ui/work-label';
import { PreferredTime } from '../../../shared/ui/preferred-time';

type View = 'manager' | 'technician';

@Component({
  selector: 'app-home',
  imports: [DatePipe, RouterLink, PageTitle, Icon, WorkLabel, PreferredTime],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class HomePage {
  protected readonly i18n = inject(I18n);
  protected readonly identity = inject(WorkspaceIdentity);
  private readonly auth = inject(AuthSession);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  protected readonly canManage = computed(() => this.auth.hasPermission('customers.view'));
  protected readonly canWork = computed(() => this.auth.hasPermission('VISIT_VIEW_ASSIGNED'));
  protected readonly canReportAdHoc = computed(() => this.auth.hasPermission('visits.report_ad_hoc_work'));
  protected readonly view = signal<View>(this.canManage() ? 'manager' : 'technician');
  protected readonly manager = signal<ManagerHomeResponse | null>(null);
  protected readonly technician = signal<TechnicianHomeResponse | null>(null);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  private readonly revision = signal(0);
  protected readonly attentionExpanded = signal(true);
  protected readonly attentionLimit = signal(3);
  protected readonly greeting = computed(() => {
    const hour = new Date().getHours();
    return this.i18n.text(hour < 12 ? 'homeGoodMorning' : hour < 18 ? 'homeGoodAfternoon' : 'homeGoodEvening');
  });
  protected readonly displayName = computed(() => this.manager()?.viewer.displayName ?? this.technician()?.viewer.displayName ?? this.identity.displayName());
  protected readonly address = (a: { addressLine1: string | null; city: string | null } | null) => [a?.addressLine1, a?.city].filter(Boolean).join(', ');
  protected readonly dateLabel = computed(() => new Intl.DateTimeFormat(this.i18n.language(), { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' }).format(new Date()));

  constructor() {
    effect((cleanup) => {
      const current = this.view();
      this.revision();
      this.auth.boundary();
      if ((current === 'manager' && !this.canManage()) || (current === 'technician' && !this.canWork())) {
        this.view.set(this.canManage() ? 'manager' : 'technician');
        return;
      }
      this.loading.set(true);
      this.failed.set(false);
      const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
      const endpoint = current === 'manager' ? '/home/manager' : '/home/technician';
      const sub = this.http.get<ManagerHomeResponse | TechnicianHomeResponse>(endpoint, {
        context: new HttpContext().set(AUTHENTICATED_REQUEST, true), params: { timeZone: zone },
      }).subscribe({
        next: (data) => {
          if (current === 'manager') this.manager.set(data as ManagerHomeResponse);
          else this.technician.set(data as TechnicianHomeResponse);
          this.loading.set(false);
        },
        error: () => { this.loading.set(false); this.failed.set(true); },
      });
      cleanup(() => sub.unsubscribe());
    });
  }

  protected setView(view: View) { this.view.set(view); }
  protected toggleAttention() { this.attentionExpanded.update((expanded) => !expanded); }
  protected showMoreAttention() { this.attentionLimit.set(10); }
  protected showLessAttention() { this.attentionLimit.set(3); }
  protected homeAttentionItems(data: ManagerHomeResponse) {
    return data.attention.items.slice(0, this.attentionLimit());
  }
  protected viewAllAttentionLabel(count: number) {
    return this.i18n.text('viewAllAttention').replace('{count}', String(count));
  }
  protected otherVisits(data: TechnicianHomeResponse) {
    const nextId = data.nextVisit?.visitId;
    return data.visits.filter((visit) => visit.visitId !== nextId);
  }
  protected refresh() { this.revision.update((n) => n + 1); }
  protected visitRoute(jobId: string, visitId?: string | null) {
    return this.router.createUrlTree(['/work/jobs', jobId], {
      queryParams: {
        ...(visitId ? { visit: visitId } : {}),
        ...(this.view() === 'technician' ? { view: 'field' } : {}),
      },
    });
  }
}
