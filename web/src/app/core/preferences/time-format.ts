import { DestroyRef, Injectable, signal } from '@angular/core';
import { inject } from '@angular/core';
import { readPreference, writePreference } from './browser-storage';

export type TimeFormat = '24H' | '12H';

export function formatTimeMinutes(minutes: number, format: TimeFormat): string {
  const hour24 = Math.floor(minutes / 60) % 24;
  const minute = minutes % 60;
  if (format === '24H') return `${String(hour24).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
  const hour12 = hour24 % 12 || 12;
  return `${String(hour12).padStart(2, '0')}:${String(minute).padStart(2, '0')} ${hour24 < 12 ? 'AM' : 'PM'}`;
}

export function parseTimeMinutes(value: string, format: TimeFormat): number | null {
  if (format === '24H') {
    const match = /^(?:([01]\d|2[0-3])):([0-5]\d)$/.exec(value.trim());
    return match ? Number(match[1]) * 60 + Number(match[2]) : null;
  }
  const match = /^(0[1-9]|1[0-2]):([0-5]\d)\s+(AM|PM)$/i.exec(value.trim());
  if (!match) return null;
  const hour = Number(match[1]) % 12 + ((match[3] ?? 'AM').toUpperCase() === 'PM' ? 12 : 0);
  return hour * 60 + Number(match[2]);
}

export function formatTimeInstant(
  value: string | Date | number,
  locale: string,
  timeZone: string | undefined,
  format: TimeFormat,
  timeZoneName?: 'shortOffset',
): string {
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const parts = new Intl.DateTimeFormat(locale, {
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone, timeZoneName,
  }).formatToParts(date);
  const hour = parts.find((part) => part.type === 'hour')?.value ?? '00';
  const minute = parts.find((part) => part.type === 'minute')?.value ?? '00';
  const time = format === '24H'
    ? `${hour.padStart(2, '0')}:${minute}`
    : `${String((Number(hour) % 24) % 12 || 12).padStart(2, '0')}:${minute} ${Number(hour) % 24 < 12 ? 'AM' : 'PM'}`;
  const zone = parts.find((part) => part.type === 'timeZoneName')?.value;
  return zone ? `${time} ${zone}` : time;
}

@Injectable({ providedIn: 'root' })
export class TimeFormatPreference {
  private readonly state = signal<TimeFormat>(this.readInitial());
  readonly format = this.state.asReadonly();

  constructor() {
    const onStorage = (event: StorageEvent) => {
      if (event.key === 'servora.time-format') this.state.set(this.readInitial());
    };
    window.addEventListener('storage', onStorage);
    inject(DestroyRef).onDestroy(() => window.removeEventListener('storage', onStorage));
  }

  setFormat(format: TimeFormat): void {
    if (format !== '12H' && format !== '24H') return;
    this.state.set(format);
    writePreference('servora.time-format', format);
  }

  formatMinutes(minutes: number, format = this.format()): string {
    return formatTimeMinutes(minutes, format);
  }

  parseMinutes(value: string, format = this.format()): number | null {
    return parseTimeMinutes(value, format);
  }

  formatInstant(value: string | Date | number | null | undefined, locale: string, timeZone?: string): string {
    return value == null || value === '' ? '' : formatTimeInstant(value, locale, timeZone, this.format());
  }

  private readInitial(): TimeFormat {
    return readPreference('servora.time-format') === '12H' ? '12H' : '24H';
  }
}
