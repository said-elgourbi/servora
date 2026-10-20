import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { Appearance } from './core/preferences/appearance';
import { I18n } from './core/i18n/i18n';
import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';
import { routes } from './app.routes';
import { AuthSession } from './features/auth/data-access/auth-session';
import { authInterceptor } from './features/auth/data-access/auth.interceptor';
registerLocaleData(localeFr);
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideRouter(routes, withComponentInputBinding()),
    provideAppInitializer(() => {
      inject(Appearance);
      inject(I18n);
      return inject(AuthSession).restore();
    }),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
