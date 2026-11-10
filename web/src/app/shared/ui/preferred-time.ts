import { Pipe, inject } from '@angular/core';
import { I18n } from '../../core/i18n/i18n';
import { TimeFormatPreference } from '../../core/preferences/time-format';

@Pipe({ name: 'preferredTime', standalone: true, pure: false })
export class PreferredTime {
  private readonly i18n = inject(I18n);
  private readonly preference = inject(TimeFormatPreference);

  transform(value: string | Date | number | null | undefined, timeZone?: string): string {
    const locale = this.i18n.language() === 'fr' ? 'fr-CA' : 'en-CA';
    return this.preference.formatInstant(value, locale, timeZone);
  }
}
