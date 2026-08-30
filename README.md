# TestForge

Self-service test data platform. An engineer asks for a dataset; TestForge
introspects the target PostgreSQL schema, topologically orders its foreign-key
graph, generates referentially consistent synthetic rows, masks the PII
deterministically, seeds it into an ephemeral database, and hands back a
connection string under a TTL lease.

**Live demo:** _deploy `console/` to Vercel and put the URL here._ It runs
entirely in the browser, with no backend required.

---

## The problem

Hand-writing fixtures for a schema with real foreign keys is miserable. You
cannot insert an `order_line` before its `order_header`, or an `order_header`
before its `customer`, or a `customer` before its `address` — and by the time
you have worked out the order, someone has added a table. Then production has
a bug you cannot reproduce because your fixtures have three customers and
production has three million.

The usual escape is a masked copy of production, which brings its own problems:
it is enormous, it is stale, and every copy is a new place PII can leak from.

TestForge generates instead of copies. It reads the *shape* of a schema and
never a row of its data, so a target can safely point at a production replica.

## How it works

```
request ─▶ introspect ─▶ plan ─▶ provision ─▶ generate ─▶ seed ─▶ verify ─▶ lease
             pg_catalog   FK order  new database   rows       batched   counts   TTL
                          + masking + role         + masking  inserts            + reaper
```

**Introspection** reads `pg_catalog` in five queries per schema rather than one
per table, so a 200-table schema costs five round trips. It picks up the things
that break naive generators: identity versus serial, `STORED` generated
columns, enum label ordering, composite and self-referencing foreign keys,
typmod-encoded precision. The structure is hashed into a fingerprint, so an
unchanged schema reuses its snapshot and a drifted one invalidates stale plans.

**Ordering** is Kahn's algorithm over child→parent foreign-key edges, with a
sorted ready-queue so the order is identical on every run — a dataset's
reproducibility depends on the traversal itself being stable. Three cases real
schemas contain are handled rather than left to fail at INSERT time:

| Case | What happens |
| --- | --- |
| Self-reference (`employee.manager_id`) | Not an ordering constraint at all. Rows are generated in order, so it points at an earlier row of the same table. |
| Cycle (`customer` ⇄ `order_header`) | Decomposed with iterative Tarjan, then broken by deferring one *nullable* edge: those columns are seeded NULL and filled by a second UPDATE pass once both tables exist. |
| Cycle with no nullable edge | Genuinely unsatisfiable. Fails with the cycle named, rather than halfway through seeding. |

**Masking** derives every value from `HMAC-SHA256(key, salt ‖ column ‖ value)`.
Three properties follow, and all three are load-bearing: *stable*, so a value
masks identically everywhere and joins survive; *irreversible* without the key;
and *unlinkable across datasets*, because a per-dataset salt means two leaked
datasets cannot be correlated.

Each strategy preserves what the schema and the application depend on while
destroying the link to the original. Masked cards still pass Luhn and masked
IBANs still pass mod-97, because code under test validates those before
anything else — an invalid one would only ever exercise the rejection path.
SSNs land in the reserved 900-999 range, emails at RFC 2606 domains that cannot
receive mail, IP addresses in TEST-NET blocks. Date shifts derive from the
*row*, so an order placed three days after signup is still three days after
signup.

**Generation** derives each cell's random stream from `(seed, table, column,
row)`, so no value depends on generation order, threading, or how many rows
precede it — row 7 is identical whether you generate 8 rows or 800. Uniqueness
is by construction from the row index rather than by retry, since retrying
would make the number of draws non-deterministic and break reproducibility.

Referential consistency comes from the ordering, not from checking afterwards:
tables seed in dependency order, each registers its keys, and children draw
from what exists rather than inventing a reference.

**Leases** answer the question every test data system eventually fails at: who
deletes this. Nobody remembers, so every database expires from the moment it
exists. A reaper runs two sweeps — an expiry sweep that claims each state
transition in the database first, so concurrent instances cannot both drop the
same database, and an orphan sweep for databases left behind by a crash between
`CREATE DATABASE` and the lease insert, which nothing would otherwise expire.

## Repository layout

```
backend/    Spring Boot service — the real implementation
console/    Angular console, and a TypeScript port of the engine for the demo
.github/    CI: unit tests, Testcontainers integration tests, Flyway checks
```

| | |
| --- | --- |
| Service | Java 21, Spring Boot 4.1, Flyway, PostgreSQL |
| Console | Angular 22, standalone components, signals, zoneless |
| Tests | 142 unit, 52 integration (Testcontainers), 51 console |

## Running it

### The console alone

```bash
cd console
npm install
npm start          # http://localhost:4200
```

With no backend configured it runs the engine in the browser, which is what the
hosted demo does.

### The full service

Needs a PostgreSQL instance. One instance can play all three roles locally —
control plane, demo target, and the cluster ephemeral databases are created on:

```bash
# any PostgreSQL 15+ will do
createdb testforge
createdb testforge_demo
psql testforge_demo -f backend/src/main/resources/db/demo/demo_commerce.sql

cd backend
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

Then point the console at it by setting `__TESTFORGE_API__` in
`console/src/index.html` to `http://localhost:8080`.

The service applies its own migrations on startup. API docs are at
`/swagger-ui.html`.

### Tests

```bash
cd backend  && ./mvnw verify    # integration tests skip without a container runtime
cd console  && npm test
```

Container-backed tests skip rather than fail when no Docker is available, so a
developer without it still gets a green run while CI executes the full set.

## About the demo

The hosted demo has no backend. The console runs the same algorithms the
service does — foreign-key ordering with cycle breaking, planning, deterministic
masking, referentially consistent generation — against a schema fixture
generated by the real Java introspector, so the fixture cannot drift from what
introspection actually produces.

Two things are necessarily simulated, because they need a database:
provisioning and seeding. Rows are generated for real and shown to you, but
nothing is written anywhere and the connection strings are decorative.
Everything upstream of that is genuinely computed in your browser.

The port is a second implementation of the algorithms, and is tested against
the same invariants as the Java: parents precede children, the cycle is broken
at its nullable edge, foreign keys resolve, seeds reproduce, cards pass Luhn,
IPs stay valid.

## Deployment

The console is a static bundle. Vercel picks up `console/vercel.json`; the
build is `npm run build` and the output is `dist/console/browser`.

The service is a normal Spring Boot application and needs a JVM and a
PostgreSQL instance. It is not deployed as part of the demo.

## License

MIT — see [LICENSE](LICENSE).
