import {
  ApplicationConfig,
  inject,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { routes } from './app.routes';
import { API_BASE_URL, TestForgeService } from './core/testforge.service';
import { HttpBackend } from './core/http-backend';
import { DemoBackend } from './demo/demo-backend';

/**
 * Chooses a backend.
 *
 * A configured API URL means talk to the Spring service. Nothing configured
 * means run the engine in the browser, which is how the hosted demo works:
 * there is no service to reach, so the console computes the graph, the plan,
 * the masking and the rows itself.
 */
function backendFactory(): TestForgeService {
  const baseUrl = inject(API_BASE_URL);
  return baseUrl ? new HttpBackend() : new DemoBackend();
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Zoneless: every component drives its own state through signals, so there
    // is nothing for zone.js to patch on their behalf.
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch()),
    provideRouter(
      routes,
      // Route params arrive as component inputs, keeping components free of
      // ActivatedRoute plumbing.
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
    ),
    HttpBackend,
    DemoBackend,
    { provide: TestForgeService, useFactory: backendFactory },
  ],
};
