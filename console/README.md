# TestForge console

Angular 22, standalone components, signals, zoneless change detection, every
feature lazily loaded.

## Two backends

The console talks to an abstract `TestForgeService`, satisfied by either:

- **`HttpBackend`** — the Spring service, when `__TESTFORGE_API__` is set in
  `src/index.html`.
- **`DemoBackend`** — the engine running in the browser, when it is not. This is
  what the hosted demo uses.

The demo backend is a TypeScript port of the algorithms that matter: foreign-key
ordering with cycle breaking, planning, deterministic HMAC masking, and
referentially consistent row generation. It works against `demo-schema.json`, a
fixture written by the real Java introspector rather than by hand, so it cannot
drift from what introspection actually produces.

Regenerate the fixture after changing the demo schema:

```bash
cd ../backend
./mvnw verify -Dit.test=DemoFixtureWriterIT -Dtestforge.writeDemoFixture=true
```

## Commands

```bash
npm start                 # dev server on :4200
npm test -- --no-watch    # vitest
npm run build             # production bundle in dist/console/browser
```

## Deploying to Vercel

Set the project's root directory to `console/`. `vercel.json` supplies the build
command, the output directory, the SPA rewrite and cache headers.

To point the deployed console at a running service instead of the browser
engine, set `globalThis.__TESTFORGE_API__` in `src/index.html` to its URL, and
add that origin to `testforge.cors.allowed-origins` on the service.
