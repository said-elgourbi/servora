import { inject } from '@angular/core';
import { AuthSession } from './features/auth/data-access/auth-session';
import { AppUpdates } from './core/updates/app-updates';
const preserveFieldEditor = () =>
  !inject(AuthSession).authenticated() || inject(AppUpdates).checkpoint();
import type { Routes } from '@angular/router';
import { authenticatedGuard, signedOutGuard } from './features/auth/auth.guards';
export const routes: Routes = [
  {
    path: 'sign-in',
    canActivate: [signedOutGuard],
    loadChildren: () => import('./features/auth/auth.routes').then((m) => m.authRoutes),
  },
  {
    path: 'forbidden',
    canActivate: [authenticatedGuard],
    data: { kind: 'forbidden' },
    loadComponent: () => import('./shared/ui/status-page/status-page').then((m) => m.StatusPage),
  },
  {
    path: '',
    canActivate: [authenticatedGuard],
    loadComponent: () => import('./layout/app-shell/app-shell').then((m) => m.AppShell),
    children: [
      {
        path: 'work/schedule',
        loadComponent: () =>
          import('./features/schedule/pages/schedule').then((m) => m.OfficeSchedule),
      },
      {
        path: 'work/customers/:customerId/jobs/new',
        loadComponent: () => import('./features/jobs/pages/job-form').then((m) => m.JobForm),
      },
      {
        path: 'work/customers/:customerId/properties/new',
        loadComponent: () =>
          import('./features/properties/pages/property-form').then((m) => m.PropertyForm),
      },
      {
        path: 'work/customers/:customerId/properties/:propertyId/edit',
        loadComponent: () =>
          import('./features/properties/pages/property-form').then((m) => m.PropertyForm),
      },
      {
        path: 'work/customers/:customerId/properties/:propertyId',
        loadComponent: () =>
          import('./features/properties/pages/property-detail').then((m) => m.PropertyDetailPage),
      },
      {
        path: 'work/customers/new',
        loadComponent: () =>
          import('./features/customers/pages/customer-form').then((m) => m.CustomerForm),
      },
      {
        path: 'work/customers/:customerId/edit',
        loadComponent: () =>
          import('./features/customers/pages/customer-form').then((m) => m.CustomerForm),
      },
      {
        path: 'work/customers/:customerId',
        loadComponent: () =>
          import('./features/customers/pages/customer-detail').then((m) => m.CustomerDetail),
      },
      {
        path: 'work/customers',
        loadComponent: () =>
          import('./features/customers/pages/customer-list').then((m) => m.CustomerList),
      },
      {
        path: 'work/report',
        canDeactivate: [preserveFieldEditor],
        loadComponent: () => import('./features/field/ui/field-report').then((m) => m.FieldReport),
      },
      {
        path: 'work/jobs/:jobId',
        canDeactivate: [preserveFieldEditor],
        loadComponent: () => import('./features/jobs/pages/job-details').then((m) => m.JobDetails),
      },
      {
        path: '',
        pathMatch: 'full',
        loadComponent: () =>
          import('./features/workspace/pages/workspace').then((m) => m.Workspace),
      },
    ],
  },
  {
    path: '**',
    loadComponent: () => import('./shared/ui/status-page/status-page').then((m) => m.StatusPage),
  },
];
