import type { Routes } from '@angular/router';
import { authenticatedGuard, permissionGuard, signedOutGuard } from './features/auth/auth.guards';
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
        path: 'home',
        loadComponent: () =>
          import('./features/home/pages/home').then((m) => m.HomePage),
      },
      {
        path: 'work/attention',
        loadComponent: () =>
          import('./features/home/pages/attention-list').then((m) => m.AttentionListPage),
      },
      {
        path: 'work/jobs/:jobId',
        loadComponent: () =>
          import('./features/jobs/pages/job-details').then((m) => m.JobDetails),
      },
      {
        path: 'work/customers',
        pathMatch: 'full',
        loadComponent: () =>
          import('./features/customers/pages/customers').then((m) => m.CustomersPage),
      },
      {
        path: 'work/customers/new',
        loadComponent: () =>
          import('./features/customers/pages/customer-form').then((m) => m.CustomerFormPage),
      },
      {
        path: 'work/customers/:customerId/edit',
        loadComponent: () =>
          import('./features/customers/pages/customer-form').then((m) => m.CustomerFormPage),
      },
      {
        path: 'work/customers/:customerId',
        loadComponent: () =>
          import('./features/customers/pages/customer-detail').then((m) => m.CustomerDetailPage),
      },
      {
        path: 'work/schedule',
        loadComponent: () =>
          import('./features/schedule/pages/schedule').then((m) => m.OfficeSchedule),
      },
      {
        path: 'work/visit-requests',
        canActivate: [permissionGuard('visits.review_requests')],
        loadComponent: () =>
          import('./features/schedule/pages/visit-requests').then((m) => m.VisitRequestsReviewPage),
      },
      {
        path: 'work/review',
        loadComponent: () =>
          import('./features/reconciliation/pages/work-review').then((m) => m.WorkReviewPage),
      },
      {
        path: 'work/reports',
        loadComponent: () =>
          import('./features/field/ui/field-report').then((m) => m.FieldReport),
      },
      {
        path: 'work/requests',
        canActivate: [permissionGuard('visits.request_follow_up')],
        loadComponent: () =>
          import('./features/field/ui/field-requests').then((m) => m.FieldRequestsPage),
      },
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'home',
      },
      {
        path: '**',
        redirectTo: 'home',
      },
    ],
  },
  {
    path: '**',
    loadComponent: () => import('./shared/ui/status-page/status-page').then((m) => m.StatusPage),
  },
];
