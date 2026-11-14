# FlowForge

Workflow API for defining steps and accepting webhook calls. A call is stored as an execution. Steps are not run yet, and RabbitMQ is not used yet.

## Stack

Java 21, Spring Boot 4, PostgreSQL 17, Flyway, RabbitMQ 4, React, TypeScript, and Vite. The API listens on `127.0.0.1:8080`.

## Run locally

Copy `.env.example` to `.env` and set `DB_USERNAME`, `DB_PASSWORD`, `RABBITMQ_USERNAME`, and `RABBITMQ_PASSWORD`. From the repo root, start Postgres and RabbitMQ with `docker compose up`. From `backend\`, run the app so it can read `../.env`.

## What it does

- Create, list, and get workflows. Activate or deactivate a workflow. Rotate its webhook token. The token is returned once; only its hash is stored.
- Add and list steps on a workflow. Step types are `VALIDATE_FIELDS` and `HTTP_REQUEST`. `HTTP_REQUEST` accepts only `GET` or `POST` and an HTTPS URL. Loopback, private, and link-local targets are rejected, including names that resolve to those addresses.
- `POST /api/webhooks` with `X-Webhook-Token` and `Idempotency-Key`. The body must be a JSON object of at most 64 KiB. A new call returns 201 and is stored as `RECEIVED`. The same key and payload returns 200. A different payload for that key returns 409.
- `GET /api/executions/{id}` returns one execution. `GET /api/workflows/{workflowId}/executions` returns a page, default `page=0` and `size=20`, at most 100. Pages are newest first. Each record has `id`, `workflowId`, `status`, and `createdAt`. The payload is not included.
- Health and info are exposed by Actuator. Backend and frontend tests run in GitHub Actions on pull requests and pushes to `main`.

There is no authentication. The frontend is still the Vite starter and does not call the API.

## Not built yet

Running a workflow after a webhook, publishing to RabbitMQ, retries, and a dashboard.
