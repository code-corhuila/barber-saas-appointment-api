# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - 2026-10-08

User stories: code-corhuila/barber-saas-docs#4, code-corhuila/barber-saas-docs#5, code-corhuila/barber-saas-docs#8, code-corhuila/barber-saas-docs#13, code-corhuila/barber-saas-docs#59, code-corhuila/barber-saas-docs#88

### Added

- domain: add the errors the domain raises
- domain: add the appointment status and which states take time
- domain: add money in cents that is never negative
- domain: add the slot computed from the service duration
- domain: add the appointment aggregate with its state machine and cancellation window
- application: take the caller only from the validated token
- application: add the page and the result of an idempotent creation
- application: declare the appointment use cases
- application: declare the appointment repository with its key and outbox in one transaction
- application: declare what appointment asks barbershop-api and schedule-api
- application: declare the clock and the id generator
- application: hash the booking request so a reused key with another body is refused
- application: build the appointment events of the domain catalog
- application: book, list, get, move and cancel appointments
- persistence: generate identifiers and read the time in the service
- persistence: add the in-memory repository for runs without a database
- persistence: page with bound values and store idempotency keys in jdbc
- persistence: store the appointment, its key and its events in one transaction
- http: call other apis with timeouts, one bounded retry, the caller token and the correlation id
- http: ask barbershop-api for the service price and duration and the barbershop policy
- http: ask schedule-api for the free slots of a barber
- http: add the shared error envelope
- http: turn every error, including a failed dependency, into one status code
- http: reuse or create the correlation id and log one line per request
- http: answer the liveness probe without a token
- security: verify rs256 tokens and keep the token to pass on
- http: require a token on every api route
- app: add the spring boot entry point
- app: wire every port to its adapter with explicit pool limits
- app: set timeouts, graceful shutdown, json logs and the other apis
- http: validate paging and the idempotency key
- http: read bodies strictly so a price or status in a booking is refused
- http: add the appointment view with hh:mm times and the page envelope
- http: expose booking, listing, transitions and cancellation of appointments
- deploy: build the image and compose the service on the shared instance
- domain: tell open appointments from the history
- application: accept only the service token an internal operation exists for
- application: declare the busy slots of a barber on a date
- application: answer the busy slots only to schedule-api
- persistence: read the open slots of a barber on a date
- http: require a token on every internal route
- http: expose the busy slots on the internal network
- appointment: let a client without a barbershop list their appointments
- application: hand the outbox to the worker as event envelopes
- persistence: read pending events and record their delivery or failure
- http: expose the outbox relay to barber-saas-worker on the internal network
- application: write tomorrow's reminders and mark past no-shows in each barbershop's date
- persistence: read confirmed appointments across barbershops and mark a reminder once
- http: read a barbershop's time zone from its public detail, without a token
- http: expose the daily jobs to barber-saas-worker on the internal network
- appointment: say who completed the appointment in AppointmentCompleted
- domain: an appointment can be paid by a reward coupon
- booking: apply the client's active reward coupon
- http: read the active reward coupon from loyalty-api
- persistence: store the coupon of an appointment and wire loyalty-api

### Fixed

- application: keep a null client id in the event payload of a walk-in

### Documentation

- readme: explain the service, how to run and test it, and what is missing
- readme: document the busy slots and drop what is already solved
- readme: point the header to Barber Saas and barber-saas-docs
- readme: note the client listing across barbershops
- readme: describe the worker's internal operations and the daily jobs

### Tests

- ci: build and test every pull request with java 21
- domain: specify booking, the slot, the price and the appointment state machine
- application: specify booking, listing, transitions and cancelling with doubles of the ports
- persistence: specify what every appointment repository must do
- persistence: check the jdbc repository against a migrated database as appointment_app
- http: specify the clients of barbershop-api and schedule-api
- security: specify which tokens are accepted and what they carry
- app: check health, the envelope and the correlation id over http
- http: specify booking, listing, transitions and cross-barbershop isolation over http
- application: specify the busy slots schedule-api reads with its service token
- persistence: specify the busy slots every repository returns
- http: specify the internal busy slots operation with real service tokens

### Maintenance

- build: ignore build output, ide files, env files and keys
- github: add the pull request template
- github: track the story environment on the board
- build: add the maven parent on spring boot 3.5 and java 21
- build: add the core module without any framework dependency
- build: add the adapters module with spring web and jdbc
- build: add the app module that composes the service
- env: list every variable without real values
- use the new repository name barber-saas-infra-postgres

[2.0.0]: https://github.com/code-corhuila/barber-saas-appointment-api/releases/tag/v2.0.0
