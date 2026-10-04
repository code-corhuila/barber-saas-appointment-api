# barber-saas-appointment-api

> appointment bounded context: service API

Part of the **LMS Library** distributed system — team `lms-library`, Grupo 2.
Governance and documentation live in [`library-docs`](https://github.com/code-corhuila/library-docs).

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `library-docs`.

---

## BarberSaaS — what this repository is

The appointment service, the heart of the app: booking an appointment without double booking and
taking it through its lifecycle (`07-api/contracts/openapi/appointment-service.yaml`,
HU-APPT-001 #4, HU-APPT-002 #8). Hexagonal, three Maven modules (ADR-012, annex C):
`appointment-core` (domain and use cases, no Spring), `appointment-adapters` (HTTP in and out,
JDBC, RS256 validation) and `appointment-app` (composition root). It never migrates its schema:
that is `barber-saas-appointment-db`.

| Operation | Who |
|---|---|
| `POST /api/v1/appointments` (`Idempotency-Key` required) | `CLIENT` books for themselves; `ADMIN_BARBERSHOP`, `BARBER` also book walk-ins (`clientId` null) |
| `GET /api/v1/appointments?page&limit&status&barberId&date`, `GET …/{id}` | `CLIENT` only their own; staff the whole barbershop |
| `POST …/{id}/confirm`, `/start`, `/complete`, `/no-show` | `ADMIN_BARBERSHOP`, `BARBER` |
| `POST …/{id}/cancel` | `CLIENT` their own, outside the barbershop's window; staff always |
| `GET /health` | liveness, no token |

Rules: the end and the price come from the service and the price never changes afterwards
(INV-APPT-002); the start must be a slot schedule-api offers; two active appointments of one
barber never overlap — checked by the service for a clean `422` and guaranteed by
`ex_appointment_no_double_booking` in the database (INV-APPT-001); only the transitions of
`08-diagrams/uml/state-appointment.md` are allowed, anything else is `422 INVALID_STATUS_TRANSITION`
(INV-APPT-004); a client cancels only before `start − cancellationPolicyHours`, in the
barbershop's time zone (INV-APPT-003). The tenant comes **only** from the token: another
barbershop's appointment, barber or service answers `404` (HU-TENANT-001 #13).

**Other domains, through their APIs (golden rule 8):** price, duration, time zone and cancellation
window from `barbershop-api`; free slots from `schedule-api`. Each call carries the caller's token
and `X-Correlation-Id`, with 2 s to connect, 3 s per attempt and one retry only when the service is
unreachable or answers 502/503/504; a failure answers `500` instead of a guessed booking.

**Events:** booking, confirming, completing, cancelling and a no-show write `AppointmentCreated`,
`AppointmentConfirmed`, `AppointmentCompleted`, `AppointmentCancelled` and
`AppointmentMarkedNoShow` to `appointment.outbox_event` in the same transaction as the change.
Completing no longer writes the income to finance: finance and loyalty consume `AppointmentCompleted`.

### How to start it

As part of the platform: `./scripts/up.sh dev` in `barber-saas-infra` (it needs `barbershop-api`
and `schedule-api`). Alone, without a database (in-memory repository), pointing at running APIs:

```bash
mvn -B -DskipTests package
JWT_PUBLIC_KEY="$(cat ../barber-saas-infra/keys/jwt-public.pem)" \
BARBERSHOP_API_URL=http://localhost:8081 SCHEDULE_API_URL=http://localhost:8082 \
  java -jar appointment-app/target/appointment-app-0.1.0.jar
```

### Where the data is

Schema `appointment` of the shared PostgreSQL instance, as `appointment_app` (`DATABASE_URL`,
`DATABASE_USER`, `DATABASE_PASSWORD`; see `.env.example`), never as the administrator. The
appointment, its idempotency key and its events are written in one transaction; a transition
updates only the status, the cancellation reason and `updated_at`.

### How it is tested

`mvn -B verify` (no Docker needed): the aggregate and its state machine, the use cases with fake
ports, the clients of the other APIs against a stub HTTP server, and the HTTP contract over the
whole service with stand-ins for barbershop-api and schedule-api, including cross-barbershop tests.
The JDBC repository — including the database refusing a double booking — is also tested against a
database migrated by `barber-saas-appointment-db` when `TEST_DATABASE_URL`, `TEST_DATABASE_USER`
and `TEST_DATABASE_PASSWORD` are set.

### What is missing

- **btree_gist.** The no-double-booking constraint needs the extension; `barber-saas-infra` must
  create it in `postgres/init/01-instance.sh` (extensions belong to the instance, Annex J J.4).
- **Clients and OQ-07.** A `CLIENT` token carries no barbershop, so booking answers `403` to clients
  until OQ-07 decides how a client is bound to a barbershop (in development, `dev-token.sh` can add it).
- **Service token.** Calls to barbershop-api and schedule-api forward the caller's token: their
  operations require a user role with a barbershop, which a `SERVICE` token does not carry.
- **Publishing the outbox.** Events are stored but not yet published: the transport is open (AT-004).
- **Not in this contract yet:** reschedule, reward coupons applied at booking, and the reminder and
  automatic no-show jobs of the prototype (the worker's).
