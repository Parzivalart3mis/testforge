import { Routes } from '@angular/router';

/**
 * Every feature is lazily loaded. The console is a static bundle served from a
 * CDN, and the schema browser in particular pulls in a fair amount of layout
 * code that the dashboard has no use for.
 */
export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
    title: 'TestForge',
  },
  {
    path: 'request',
    loadComponent: () => import('./features/request/request-dataset').then((m) => m.RequestDataset),
    title: 'Request a dataset - TestForge',
  },
  {
    path: 'schemas',
    loadComponent: () => import('./features/schema/schema-browser').then((m) => m.SchemaBrowser),
    title: 'Schemas - TestForge',
  },
  {
    path: 'schemas/:targetId',
    loadComponent: () => import('./features/schema/schema-browser').then((m) => m.SchemaBrowser),
    title: 'Schemas - TestForge',
  },
  {
    path: 'datasets',
    loadComponent: () => import('./features/datasets/dataset-list').then((m) => m.DatasetList),
    title: 'Datasets - TestForge',
  },
  {
    path: 'datasets/:id',
    loadComponent: () => import('./features/datasets/dataset-detail').then((m) => m.DatasetDetail),
    title: 'Dataset - TestForge',
  },
  {
    path: 'jobs/:id',
    loadComponent: () => import('./features/jobs/job-detail').then((m) => m.JobDetail),
    title: 'Job - TestForge',
  },
  {
    path: 'leases',
    loadComponent: () => import('./features/leases/lease-list').then((m) => m.LeaseList),
    title: 'Leases - TestForge',
  },
  { path: '**', redirectTo: '' },
];
