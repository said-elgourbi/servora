import { filter, map } from 'rxjs';
import { BreakpointObserver } from '@angular/cdk/layout';
import { toSignal } from '@angular/core/rxjs-interop';
import { WorkspaceIdentity } from '../../features/workspace/data-access/workspace-identity';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router, NavigationEnd, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
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
      <a routerLink="/" class="wordmark"
        ><span class="brand-mark" aria-hidden="true">s</span>Servora<span aria-hidden="true"
          >.</span
        ></a
      >
      <nav [attr.aria-label]="i18n.text('navigation')">
        <a
          routerLink="/"
          routerLinkActive="active"
          [routerLinkActiveOptions]="{
            paths: 'exact',
            queryParams: 'ignored',
            matrixParams: 'ignored',
            fragment: 'ignored',
          }"
          ariaCurrentWhenActive="page"
          ><app-icon name="home" />{{ i18n.text('home') }}<app-icon name="arrow"
        /></a>
        @if (session.hasPermission('customers.view')) {
          <a routerLink="/work/customers" routerLinkActive="active" ariaCurrentWhenActive="page"
            ><app-icon name="briefcase" />{{ i18n.text('customers') }}<app-icon name="arrow"
          /></a>
        }
        @if (session.hasPermission('schedule.view_org')) {
          <a routerLink="/work/schedule" routerLinkActive="active" ariaCurrentWhenActive="page"
            ><app-icon name="briefcase" />{{ i18n.text('officeSchedule') }}<app-icon name="arrow"
          /></a>
        }
      </nav>
    </aside>
    <div class="workspace-body">
      <header>
        <a routerLink="/" class="mobile-brand wordmark">Servora<span aria-hidden="true">.</span></a>
        <span class="header-context"><app-icon name="briefcase" />{{ i18n.text(section()) }}</span>
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
              @if (session.hasPermission('customers.view')) {
                <a mat-button routerLink="/work/customers">{{ i18n.text('customers') }}</a>
              }
              @if (session.hasPermission('schedule.view_org')) {
                <a mat-button routerLink="/work/schedule">{{ i18n.text('officeSchedule') }}</a>
              }
              <app-preferences /><button mat-stroked-button (click)="session.signOut()">
                {{ i18n.text('signOut') }}
              </button>
            </div>
          </details>
        }
      </header>
      <main
        id="main-content"
        [class.dispatch-workspace]="section() === 'officeSchedule'"
        tabindex="-1"
      >
        <router-outlet />
      </main>
    </div>
  }`,
  styleUrl: './app-shell.scss',
})
export class AppShell {
  private readonly router = inject(Router);
  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );
  protected readonly section = computed(() =>
    this.currentUrl().startsWith('/work/schedule')
      ? 'officeSchedule'
      : this.currentUrl().startsWith('/work/customers')
        ? 'customers'
        : 'home',
  );
  protected readonly compact = toSignal(inject(BreakpointObserver).observe('(max-width: 900px)'));
  protected readonly identity = inject(WorkspaceIdentity);
  protected readonly session = inject(AuthSession);
  protected readonly i18n = inject(I18n);
}
