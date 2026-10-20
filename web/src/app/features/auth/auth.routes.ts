import type { Routes } from '@angular/router';
export const authRoutes: Routes = [
  { path: '', loadComponent: () => import('./pages/sign-in/sign-in').then((m) => m.SignIn) },
];
