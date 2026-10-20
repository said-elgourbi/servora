import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import type { WorkspaceJobDetails } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { Icon } from '../../../shared/ui/icon';

@Component({
  selector: 'app-job-contact',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './job-contact.html',
  styleUrl: './job-contact.scss',
})
export class JobContact {
  readonly value = input.required<NonNullable<WorkspaceJobDetails['customerContactDetails']>>();
  readonly customer = input.required<string>();
  protected readonly i18n = inject(I18n);
}
