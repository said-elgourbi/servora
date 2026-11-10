import { WorkspaceIdentity } from '../../features/workspace/data-access/workspace-identity';
import { BreakpointObserver } from '@angular/cdk/layout';
import { toSignal } from '@angular/core/rxjs-interop';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { I18n } from '../../core/i18n/i18n';
import { AuthSession } from '../../features/auth/data-access/auth-session';
import { Icon } from '../../shared/ui/icon';
import { Preferences } from '../../shared/ui/preferences/preferences';
@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, MatButtonModule, Preferences, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (session.authenticated()) {
    <aside>
      <a routerLink="/home" class="wordmark"
        ><span class="brand-mark" aria-hidden="true">s</span>Servora<span aria-hidden="true"
          >.</span
        ></a
      >
      <nav [attr.aria-label]="i18n.text('navigation')">
        <a routerLink="/home" routerLinkActive="active" ariaCurrentWhenActive="page"
          ><app-icon name="home" />{{ i18n.text('homeTitle') }}<app-icon name="arrow" /></a>
        @if (session.hasPermission('schedule.view_org')) { <a
          routerLink="/work/schedule"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
          ><app-icon name="briefcase" />{{ i18n.text('officeSchedule') }}<app-icon name="arrow"
          /></a> }
        @if (session.hasPermission('visits.review_requests')) { <a routerLink="/work/visit-requests" routerLinkActive="active" ariaCurrentWhenActive="page"
          ><app-icon name="briefcase" />{{ i18n.text('visitRequestReviewTitle') }}<app-icon name="arrow" /></a> }
        @if (session.hasPermission('visits.review_ad_hoc_work')) { <a routerLink="/work/review" routerLinkActive="active" ariaCurrentWhenActive="page"
          ><app-icon name="briefcase" />{{ i18n.text('reviewTitle') }}<app-icon name="arrow" /></a> }
        @if (session.hasPermission('visits.report_ad_hoc_work')) { <a routerLink="/work/reports" routerLinkActive="active" ariaCurrentWhenActive="page"
          ><app-icon name="briefcase" />{{ i18n.text('fieldMyReports') }}<app-icon name="arrow" /></a> }
        @if (session.hasPermission('visits.request_follow_up')) { <a routerLink="/work/requests" routerLinkActive="active" ariaCurrentWhenActive="page"
          ><app-icon name="briefcase" />{{ i18n.text('fieldMyRequests') }}<app-icon name="arrow" /></a> }
        @if (session.hasPermission('customers.view')) { <a routerLink="/work/customers" routerLinkActive="active" ariaCurrentWhenActive="page"
          ><app-icon name="people" />{{ i18n.text('customers') }}<app-icon name="arrow" />
        </a> }
      </nav>
    </aside>
    <div class="workspace-body">
      <header>
        <a routerLink="/home" class="mobile-brand wordmark">Servora<span aria-hidden="true">.</span></a>
        <span class="header-context"><app-icon name="briefcase" />{{ i18n.text(headerContext()) }}</span>
        @if (!compact()?.matches) {
          <div class="actions">
            <app-preferences /><span class="account"
              ><span class="account-avatar" aria-hidden="true">{{
                (identity.displayName() || i18n.text('account')).slice(0, 1)
              }}</span
              >{{ identity.displayName() || i18n.text('account') }}</span
            ><button mat-stroked-button (click)="session.signOut()">
              {{ i18n.text('signOut') }}
            </button>
          </div>
        } @else {
          <details class="mobile-menu">
            <summary>
              <span class="account-avatar" aria-hidden="true">{{
                (identity.displayName() || i18n.text('account')).slice(0, 1)
              }}</span
              >{{ identity.displayName() || i18n.text('account') }}<app-icon name="arrow" />
            </summary>
            <div class="menu-content">
            <a mat-button routerLink="/home">{{ i18n.text('homeTitle') }}</a>
            @if (session.hasPermission('schedule.view_org')) { <a mat-button routerLink="/work/schedule">{{ i18n.text('officeSchedule') }}</a> }
            @if (session.hasPermission('visits.review_requests')) { <a mat-button routerLink="/work/visit-requests">{{ i18n.text('visitRequestReviewTitle') }}</a> }
            @if (session.hasPermission('visits.review_ad_hoc_work')) { <a mat-button routerLink="/work/review">{{ i18n.text('reviewTitle') }}</a> }
            @if (session.hasPermission('visits.report_ad_hoc_work')) { <a mat-button routerLink="/work/reports">{{ i18n.text('fieldMyReports') }}</a> }
            @if (session.hasPermission('visits.request_follow_up')) { <a mat-button routerLink="/work/requests">{{ i18n.text('fieldMyRequests') }}</a> }
            @if (session.hasPermission('customers.view')) { <a mat-button routerLink="/work/customers">{{ i18n.text('customers') }}</a> }
              <app-preferences /><button mat-stroked-button (click)="session.signOut()">
                {{ i18n.text('signOut') }}
              </button>
            </div>
          </details>
        }
      </header>
      <main id="main-content" class="dispatch-workspace" tabindex="-1">
        <router-outlet />
      </main>
    </div>
  }`,
  styleUrl: './app-shell.scss',
})
export class AppShell {
  protected readonly compact = toSignal(inject(BreakpointObserver).observe('(max-width: 900px)'));
  protected readonly identity = inject(WorkspaceIdentity);
  protected readonly router = inject(Router);
  protected readonly session = inject(AuthSession);
  protected readonly i18n = inject(I18n);
  protected headerContext(): import('../../core/i18n/en').TranslationKey {
    const path = this.router.url;
    if (path.includes('/work/review')) return 'reviewTitle';
    if (path.includes('/work/visit-requests')) return 'visitRequestReviewTitle';
    if (path.includes('/work/reports')) return 'fieldMyReports';
    if (path.includes('/work/requests')) return 'fieldMyRequests';
    if (path.includes('/work/schedule')) return this.session.hasPermission('schedule.view_org') ? 'officeSchedule' : 'technicianSchedule';
    if (path.includes('/work/customers')) return 'customers';
    if (path.includes('/work/jobs')) return 'jobDetails';
    return 'homeTitle';
  }
}
