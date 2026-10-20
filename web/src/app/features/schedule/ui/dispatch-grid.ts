import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatTooltipModule } from '@angular/material/tooltip';
import { I18n } from '../../../core/i18n/i18n';
import { WorkLabel } from '../../../shared/ui/work-label';
import { addressText } from '../../../shared/ui/work-format';
import { type DispatchBoard, visitTime } from './dispatch-model';
@Component({
  selector: 'app-dispatch-grid',
  imports: [RouterLink, MatTooltipModule, WorkLabel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dispatch-grid.html',
  styleUrl: './dispatch-grid.scss',
})
export class DispatchGrid {
  readonly board = input.required<DispatchBoard>();
  readonly zone = input.required<string>();
  protected readonly hovered = signal<string | null>(null);
  protected readonly focused = signal<string | null>(null);
  protected readonly active = computed(() => this.focused() ?? this.hovered());
  protected readonly i18n = inject(I18n);
  protected readonly columns = computed(() => {
    const zone = this.zone(),
      language = this.i18n.language();
    const clock = new Intl.DateTimeFormat(language, {
      timeZone: zone,
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    });
    const offsetClock = new Intl.DateTimeFormat(language, {
      timeZone: zone,
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
      timeZoneName: 'shortOffset',
    });
    const range = (start: string, end: string) => {
      const from = new Date(start),
        to = new Date(end);
      const offset = (date: Date) =>
        offsetClock.formatToParts(date).find((part) => part.type === 'timeZoneName')?.value;
      const format = offset(from) === offset(to) ? clock : offsetClock;
      return `${format.format(from)} – ${format.format(to)}`;
    };
    return this.board().columns.map((column) => ({
      ...column,
      entries: column.entries.map((entry) => ({
        ...entry,
        exactTime: visitTime(entry.visit, zone, language),
        clockTime: range(entry.visit.scheduledStart!, entry.visit.scheduledEnd!),
        location: addressText(entry.visit.address),
        crew: entry.visit.technicians
          .map((member) => member.name || this.i18n.text('unnamedMember'))
          .join(', '),
      })),
    }));
  });
  private readonly formatter = computed(
    () =>
      new Intl.DateTimeFormat(this.i18n.language(), {
        timeZone: this.zone(),
        hour: '2-digit',
        minute: '2-digit',
        hourCycle: 'h23',
        timeZoneName: 'shortOffset',
      }),
  );
  protected tick(instant: number) {
    return this.formatter().format(instant);
  }
}
