import { PageTitle } from './shared/ui/page-title';
import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { AuthSession } from './features/auth/data-access/auth-session';
import { I18n } from './core/i18n/i18n';
import { Connectivity } from './core/connectivity/connectivity';
import { AppUpdates } from './core/updates/app-updates';
import { Preferences } from './shared/ui/preferences/preferences';
import { NavigationFocus } from './core/accessibility/navigation-focus';
@Component({
  selector: 'app-root',
  imports: [PageTitle, RouterOutlet, MatButtonModule, Preferences],
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly navigationFocus = inject(NavigationFocus);
  protected readonly session = inject(AuthSession);
  protected readonly i18n = inject(I18n);
  protected readonly connectivity = inject(Connectivity);
  protected readonly updates = inject(AppUpdates);
  private readonly router = inject(Router);
  constructor() {
    effect(() => {
      if (
        this.session.status() === 'signed-out' &&
        this.router.navigated &&
        this.router.url !== '/sign-in' &&
        !this.router.url.startsWith('/sign-in?')
      ) {
        void this.router.navigate(['/sign-in']);
      }
    });
  }
}
