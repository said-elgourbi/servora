import { DOCUMENT } from '@angular/common';
import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { readPreference, writePreference } from './browser-storage';
export type AppearanceMode = 'light' | 'dark' | 'system';
@Injectable({ providedIn: 'root' })
export class Appearance {
  private readonly document = inject(DOCUMENT);
  private readonly media = window.matchMedia('(prefers-color-scheme: dark)');
  private readonly modeState = signal<AppearanceMode>(this.initialMode());
  readonly mode = this.modeState.asReadonly();
  constructor() {
    this.apply();
    const listener = () => this.apply();
    this.media.addEventListener('change', listener);
    inject(DestroyRef).onDestroy(() => this.media.removeEventListener('change', listener));
  }
  setMode(mode: AppearanceMode): void {
    this.modeState.set(mode);
    writePreference('servora.appearance', mode);
    this.apply();
  }
  private initialMode(): AppearanceMode {
    const saved = readPreference('servora.appearance');
    return saved === 'light' || saved === 'dark' ? saved : 'system';
  }
  private apply(): void {
    this.document.documentElement.dataset['theme'] =
      this.mode() === 'system' ? (this.media.matches ? 'dark' : 'light') : this.mode();
  }
}
