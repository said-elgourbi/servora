import { DOCUMENT } from '@angular/common';
import { inject, Injectable, signal } from '@angular/core';
import { en, type TranslationKey, type Dictionary } from './en';
import { fr } from './fr';
import { readPreference, writePreference } from '../preferences/browser-storage';
export type Language = 'en' | 'fr';
const dictionaries: Record<Language, Dictionary> = { en, fr };
@Injectable({ providedIn: 'root' })
export class I18n {
  private readonly document = inject(DOCUMENT);
  private readonly languageState = signal<Language>(this.initialLanguage());
  readonly language = this.languageState.asReadonly();
  constructor() {
    this.document.documentElement.lang = this.language();
  }
  text(key: TranslationKey): string {
    return dictionaries[this.language()][key] ?? en[key];
  }
  setLanguage(value: Language): void {
    this.languageState.set(value);
    this.document.documentElement.lang = value;
    writePreference('servora.language', value);
  }
  private initialLanguage(): Language {
    const saved = readPreference('servora.language');
    return saved === 'en' || saved === 'fr'
      ? saved
      : navigator.language.startsWith('fr')
        ? 'fr'
        : 'en';
  }
}
