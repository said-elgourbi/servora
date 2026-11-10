import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatTooltipModule } from '@angular/material/tooltip';
import { I18n } from '../../../core/i18n/i18n';
import { WorkLabel } from '../../../shared/ui/work-label';
import { addressText } from '../../../shared/ui/work-format';
import { type DispatchBoard, visitTime } from './dispatch-model';
import { TimeFormatPreference, formatTimeInstant } from '../../../core/preferences/time-format';
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
  private readonly timePreference = inject(TimeFormatPreference);
  protected readonly columns = computed(() => {
    const zone = this.zone(),
      language = this.i18n.language(),
      locale = language === 'fr' ? 'fr-CA' : 'en-CA',
      format = this.timePreference.format();
    const range = (start: string, end: string) => {
      const from = new Date(start),
        to = new Date(end);
      const fromOffset = new Intl.DateTimeFormat(locale, { timeZone: zone, timeZoneName: 'shortOffset' }).formatToParts(from).find((part) => part.type === 'timeZoneName')?.value;
      const toOffset = new Intl.DateTimeFormat(locale, { timeZone: zone, timeZoneName: 'shortOffset' }).formatToParts(to).find((part) => part.type === 'timeZoneName')?.value;
      const withOffset = fromOffset !== toOffset;
      const fromTime = formatTimeInstant(from, locale, zone, format, withOffset ? 'shortOffset' : undefined);
      const toTime = formatTimeInstant(to, locale, zone, format, withOffset ? 'shortOffset' : undefined);
      return `${fromTime} – ${toTime}`;
    };
    return this.board().columns.map((column) => ({
      ...column,
      entries: column.entries.map((entry) => ({
        ...entry,
        exactTime: visitTime(entry.visit, zone, language, format),
        clockTime: range(entry.visit.scheduledStart!, entry.visit.scheduledEnd!),
        location: addressText(entry.visit.address),
        crew: entry.visit.technicians
          .map((member) => member.name || this.i18n.text('unnamedMember'))
          .join(', '),
      })),
    }));
  });
  protected tick(instant: number) {
    const locale = this.i18n.language() === 'fr' ? 'fr-CA' : 'en-CA';
    return formatTimeInstant(instant, locale, this.zone(), this.timePreference.format(), 'shortOffset');
  }
}
