import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { ScheduleVisit } from '@servora/shared-types';
import type { TranslationKey } from '../../../core/i18n/en';
import { I18n } from '../../../core/i18n/i18n';
import { WorkLabel } from '../../../shared/ui/work-label';
import { WorkStatus } from '../../../shared/ui/work-status';
import { addressText } from '../../../shared/ui/work-format';
import { visitTime } from './dispatch-model';
@Component({
  selector: 'app-schedule-list',
  imports: [RouterLink, WorkLabel, WorkStatus],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './schedule-list.html',
  styleUrl: './schedule-list.scss',
})
export class ScheduleList {
  readonly visits = input.required<ScheduleVisit[]>();
  readonly zone = input.required<string>();
  readonly compact = input(false);
  readonly overlaps = input<ReadonlySet<string>>(new Set());
  protected readonly headings: TranslationKey[] = [
    'scheduleTime',
    'scheduleJob',
    'scheduleCustomer',
    'scheduleLocation',
    'crew',
    'jobVisitPurpose',
    'scheduleStatus',
  ];
  protected readonly i18n = inject(I18n);
  protected readonly rows = computed(() =>
    this.visits().map((visit) => ({
      ...visit,
      timeLabel: visitTime(visit, this.zone(), this.i18n.language()),
    })),
  );
  protected readonly address = addressText;
}
