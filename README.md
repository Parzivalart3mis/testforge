# TestForge — Self-Service Test Data Platform

Engineers ask for a dataset. TestForge introspects the target PostgreSQL schema,
topologically orders the foreign-key graph, generates referentially consistent
synthetic rows, masks PII deterministically, seeds it into an ephemeral database,
and hands back a connection string with a TTL lease.

> **Status:** under active construction. See [`docs/`](docs/) for the design notes.

## Stack

| Layer         | Technology                                        |
| ------------- | ------------------------------------------------- |
| Service       | Java 21, Spring Boot, Flyway                       |
| Console       | Angular, TypeScript                                |
| Metadata      | PostgreSQL (RDS)                                   |
| Job state     | DynamoDB                                           |
| Snapshots     | S3                                                 |
| Runtime       | AWS ECS (Fargate), Terraform                       |
| CI            | GitHub Actions, Testcontainers                     |

## Repository layout

```
backend/    Spring Boot service — introspection, generation, seeding, leases
console/    Angular console — requests, schema browser, lease management
infra/      Terraform — ECS, RDS, DynamoDB, S3, networking
docs/       Architecture and design notes
```

## License

MIT — see [LICENSE](LICENSE).
