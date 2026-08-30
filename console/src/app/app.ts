import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly nav = [
    { path: '/', label: 'Overview', exact: true },
    { path: '/request', label: 'Request', exact: false },
    { path: '/schemas', label: 'Schemas', exact: false },
    { path: '/datasets', label: 'Datasets', exact: false },
    { path: '/leases', label: 'Leases', exact: false },
  ];
}
