# Job Queue — project rules

Database-backed job queue (Java 21, Spring Boot 3, Hibernate 6, PostgreSQL).
Built as a monolith first, designed to split into microservices later.

Domain: an online store's order pipeline. Placing an order enqueues jobs
(CHARGE_PAYMENT, SEND_ORDER_EMAIL, GENERATE_INVOICE_PDF, NOTIFY_WAREHOUSE).
External services (payment gateway, email) are stubs that can fail on purpose.

Base package: com.aadil.shopqueue

## Architecture rules
- Package by feature: job/, worker/, client/, order/, common/
- worker/ must NEVER import from job/. Workers use JobQueueClient only.
- order/ must NEVER import from job/; it enqueues via JobQueueClient.
  job/ must NEVER import from order/ — the queue stays domain-agnostic.
- Package rules are enforced by ArchitectureTest (ArchUnit).
- Controller → Service → Repository. No business logic in controllers.
- Schema changes only via Flyway migrations. ddl-auto is `validate`.
- Job claiming uses FOR UPDATE SKIP LOCKED. Complete/fail/heartbeat are
  fenced by locked_by = workerId.

## Code conventions
- DTOs are Java records. Never return entities from controllers.
- Constructor injection only, no field @Autowired.
- Every new feature gets tests. Integration tests extend PostgresIntegrationTest.

## Commands
- Build and test: ./mvnw clean verify
- Run locally: docker compose up -d postgres && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local