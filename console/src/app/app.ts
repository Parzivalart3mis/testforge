import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TestForgeService } from './core/testforge.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  /** True when the engine is running in the browser rather than against a service. */
  protected readonly isDemo = inject(TestForgeService).isDemo;

  protected readonly nav = [
    { path: '/', label: 'Overview', exact: true },
    { path: '/request', label: 'Request', exact: false },
    { path: '/schemas', label: 'Schemas', exact: false },
    { path: '/datasets', label: 'Datasets', exact: false },
    { path: '/leases', label: 'Leases', exact: false },
  ];
}
