import { TestBed } from '@angular/core/testing';
import { I18n } from './i18n';
import { en } from './en';
import { fr } from './fr';
afterEach(() => localStorage.clear());
it('keeps dictionaries complete and switches document and persisted language', () => {
  expect(Object.keys(fr).sort()).toEqual(Object.keys(en).sort());
  localStorage.setItem('servora.language', 'fr');
  const i18n = TestBed.inject(I18n);
  expect(i18n.text('signIn')).toBe('Se connecter');
  expect(document.documentElement.lang).toBe('fr');
  i18n.setLanguage('en');
  expect(document.documentElement.lang).toBe('en');
  expect(localStorage.getItem('servora.language')).toBe('en');
});
