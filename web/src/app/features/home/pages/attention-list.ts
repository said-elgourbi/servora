import { DatePipe } from '@angular/common';
import { HttpClient, HttpContext } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { ManagerHomeResponse } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';
import { AuthSession } from '../../auth/data-access/auth-session';
import { PageTitle } from '../../../shared/ui/page-title';
import { Icon } from '../../../shared/ui/icon';
import { WorkLabel } from '../../../shared/ui/work-label';
import { PreferredTime } from '../../../shared/ui/preferred-time';

const PAGE_SIZE = 20;

@Component({
  selector: 'app-attention-list',
  imports: [DatePipe, RouterLink, PageTitle, Icon, WorkLabel, PreferredTime],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './attention-list.html',
  styleUrl: './attention-list.scss',
})
export class AttentionListPage {
  protected readonly i18n = inject(I18n);
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthSession);
  protected readonly page = signal(0);
  protected readonly data = signal<ManagerHomeResponse | null>(null);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  private readonly revision = signal(0);
  protected readonly canManage = computed(() => this.auth.hasPermission('customers.view'));
  protected readonly rangeStart = computed(() => this.data()?.attention.total ? this.page() * PAGE_SIZE + 1 : 0);
  protected readonly rangeEnd = computed(() => Math.min((this.page() + 1) * PAGE_SIZE, this.data()?.attention.total ?? 0));
  protected readonly pageCount = computed(() => Math.ceil((this.data()?.attention.total ?? 0) / PAGE_SIZE));
  protected readonly rangeLabel = computed(() => this.i18n.text('attentionListPage')
    .replace('{start}', String(this.rangeStart()))
    .replace('{end}', String(this.rangeEnd()))
    .replace('{count}', String(this.data()?.attention.total ?? 0)));

  constructor() {
    effect((cleanup) => {
      this.revision();
      const currentPage = this.page();
      this.auth.boundary();
      if (!this.canManage()) { this.loading.set(false); this.failed.set(true); return; }
      this.loading.set(true);
      this.failed.set(false);
      const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
      const sub = this.http.get<ManagerHomeResponse>('/home/manager', {
        context: new HttpContext().set(AUTHENTICATED_REQUEST, true),
        params: { timeZone, attentionOffset: String(currentPage * PAGE_SIZE) },
      }).subscribe({
        next: (result) => { this.data.set(result); this.loading.set(false); },
        error: () => { this.data.set(null); this.loading.set(false); this.failed.set(true); },
      });
      cleanup(() => sub.unsubscribe());
    });
  }

  protected previous() { if (this.page() > 0) this.page.update((n) => n - 1); }
  protected next() { if (this.page() + 1 < this.pageCount()) this.page.update((n) => n + 1); }
  protected retry() { this.revision.update((n) => n + 1); }
  protected jobRoute(jobId: string, visitId: string | null) {
    return `/work/jobs/${encodeURIComponent(jobId)}${visitId ? `?visit=${encodeURIComponent(visitId)}` : ''}`;
  }
}
