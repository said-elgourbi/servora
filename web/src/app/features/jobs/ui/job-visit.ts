import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import type { WorkspaceJobDetails } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { Icon } from '../../../shared/ui/icon';
import { WorkLabel } from '../../../shared/ui/work-label';
import { WorkStatus } from '../../../shared/ui/work-status';

@Component({
  selector: 'app-job-visit',
  imports: [DatePipe, Icon, WorkLabel, WorkStatus],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './job-visit.html',
  styleUrl: './job-visit.scss',
})
export class JobVisit {
  readonly value = input.required<WorkspaceJobDetails['visits'][number]>();
  readonly attention = input<string[]>([]);
  readonly selected = input(false);
  protected readonly i18n = inject(I18n);
}
