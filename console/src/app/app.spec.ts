import { describe, expect, it } from 'vitest';
import { ApplicationRef, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from './app.routes';
import { App } from './app';
import { TestForgeService } from './core/testforge.service';
import { DemoBackend } from './demo/demo-backend';

/**
 * Renders the real components against the browser engine.
 *
 * Templates fail at runtime, not at build time: a missing pipe, a property that
 * does not exist, a null dereference in an @if all compile happily and then
 * produce a blank page. These render each route for real and assert something
 * from the engine actually reached the DOM.
 */

function configure() {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter(routes),
      DemoBackend,
      { provide: TestForgeService, useExisting: DemoBackend },
    ],
  });
}

/** Lets the lazy route, its data fetches and their simulated latency settle. */
async function settle(): Promise<void> {
  for (let i = 0; i < 12; i++) {
    await new Promise((resolve) => setTimeout(resolve, 60));
    await TestBed.inject(ApplicationRef).whenStable();
  }
}

describe('the console', () => {
  it('renders the shell with the browser-demo notice', async () => {
    configure();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('TestForge');
    expect(text).toContain('browser demo');
    expect(text).toContain('Running entirely in your browser');
  });

  it('renders the dashboard', async () => {
    configure();
    const harness = await RouterTestingHarness.create('/');
    await settle();

    const text = harness.routeNativeElement?.textContent ?? '';
    expect(text).toContain('Overview');
    expect(text).toContain('Datasets requested');
    // The demo target should have loaded from the engine.
    expect(text).toContain('Demo Commerce');
  });

  it('renders the schema browser with the seed order and the detected cycle', async () => {
    configure();
    const harness = await RouterTestingHarness.create('/schemas');
    await settle();

    const text = harness.routeNativeElement?.textContent ?? '';
    expect(text).toContain('Seed order');
    // 22 tables, and the cycle the demo schema deliberately contains.
    expect(text).toContain('order_header');
    expect(text).toContain('foreign-key cycle');
    expect(text).toContain('level 0');
  });

  it('renders the request form with the schema loaded', async () => {
    configure();
    const harness = await RouterTestingHarness.create('/request');
    await settle();

    const text = harness.routeNativeElement?.textContent ?? '';
    expect(text).toContain('Request a dataset');
    expect(text).toContain('Preview plan');
    // The classifier's sensitive columns should be listed for review.
    expect(text).toMatch(/columns classified sensitive/);
  });

  it('renders the dataset list', async () => {
    configure();
    const harness = await RouterTestingHarness.create('/datasets');
    await settle();

    expect(harness.routeNativeElement?.textContent ?? '').toContain('Datasets');
  });

  it('renders the lease list', async () => {
    configure();
    const harness = await RouterTestingHarness.create('/leases');
    await settle();

    const text = harness.routeNativeElement?.textContent ?? '';
    expect(text).toContain('Leases');
    expect(text).toContain('No active leases');
  });
});
