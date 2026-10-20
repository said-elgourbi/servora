import { PageTitle } from '../page-title';
import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { I18n } from '../../../core/i18n/i18n';
@Component({
  selector: 'app-status-page',
  imports: [PageTitle, RouterLink, MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<main id="main-content" class="status-page" tabindex="-1">
    <h1 [appPageTitle]="i18n.text(kind() === 'forbidden' ? 'forbidden' : 'notFound')">
      {{ i18n.text(kind() === 'forbidden' ? 'forbidden' : 'notFound') }}
    </h1>
    <p>{{ i18n.text(kind() === 'forbidden' ? 'forbiddenDescription' : 'notFoundDescription') }}</p>
    <a mat-flat-button routerLink="/">{{ i18n.text('backHome') }}</a>
  </main>`,
})
export class StatusPage {
  readonly kind = input<'forbidden' | 'not-found'>('not-found');
  protected readonly i18n = inject(I18n);
}
