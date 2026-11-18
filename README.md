# FlowForge

Workflow API and dashboard. An owner defines steps, accepts webhook calls, and can start a run from the dashboard. A run is stored as an execution and published through a transactional outbox to RabbitMQ. Accepting an execution does not mean the workflow has finished.

## Stack

Java 21, Spring Boot 4, PostgreSQL 17, Flyway, RabbitMQ 4, Keycloak, React, TypeScript, and Vite. The API listens on `127.0.0.1:8080`. The dashboard dev server listens on `http://localhost:5173` and proxies `/api`, `/oauth2`, and `/login/oauth2` to the API.

## Run locally

Copy `.env.example` to `.env`. Fill in `DB_USERNAME`, `DB_PASSWORD`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `KEYCLOAK_ADMIN`, and `KEYCLOAK_ADMIN_PASSWORD`. Set `FLOWFORGE_OIDC_CLIENT_SECRET` only in `.env`. Leave `FLOWFORGE_OIDC_PUBLIC_KEY_LOCATION` empty. Do not commit `.env`.

From the repo root, start Postgres, RabbitMQ, and Keycloak with `docker compose up`. Postgres is published on `127.0.0.1:5433`. RabbitMQ is published on `127.0.0.1:5672`, with management on `127.0.0.1:15672`. Keycloak is published on `127.0.0.1:8180`.

From `backend\`, run the app so it can read `../.env`. From `frontend\`, install dependencies and run `npm run dev`. Sign-in uses the server-managed BFF session: the browser keeps a session cookie, and the API keeps the tokens. The dashboard client id is `flowforge-bff`. Cookie-changing requests send the CSRF header from `GET /api/auth/csrf`.

Leave `frontend/.env` unset for `VITE_API_BASE_URL` when using that dev server, so the session cookie stays on the dashboard origin.

## What it does

- Sign in through `GET /api/auth/login`. `GET /api/auth/me` returns the signed-in profile. API calls without a session are 401. A cookie session without a valid CSRF token is rejected. There is no dev auth bypass.
- Workflows belong to the signed-in owner's subject and issuer. Another user's workflow or execution is 404. New workflows start inactive.
- Create, list, and get workflows. Activate or deactivate a workflow. Rotate its webhook token. The token is returned once from that rotation call; only its hash is stored. The dashboard does not keep webhook tokens.
- `GET /api/workflows` pages with `page` (default 0) and `size` (default 20, max 100). Optional `q` matches a case-insensitive name, or an exact id when the whole query is a positive integer. Optional `active` is `true` or `false`; omit it for both. The dashboard search box waits 300ms, then stores `q`, `status`, and `page` in the address bar.
- Add and list steps. Step types are `VALIDATE_FIELDS` and `HTTP_REQUEST`. `HTTP_REQUEST` accepts only `GET` or `POST` and an HTTPS URL. Loopback, private, and link-local targets are rejected, including names that resolve to those addresses. An HTTPS GET runs only when its host is named in `flowforge.http-get.allowed-hosts`. There is no allow-all value.
- `POST /api/webhooks` with `X-Webhook-Token` and `Idempotency-Key`. The body must be a JSON object of at most 64 KiB. A new call returns 201 and is stored as `RECEIVED`. The same key and payload returns 200. A different payload for that key returns 409. Webhook calls are not session calls.
- `POST /api/workflows/{id}/executions` starts a manual run for the signed-in owner. It does not use the webhook token. The body is a JSON object with the same 64 KiB cap. The workflow must be active. The dashboard sends `Idempotency-Key`. If that header is missing, the server derives a stable key from the workflow id and the raw body so a retry of the same submission does not create a second execution. The response is the execution id and initial status, not the payload. A new run is 201 and `RECEIVED`. The same key and payload is 200. The request writes an outbox row; it does not run the steps inline.
- `GET /api/executions/{id}` returns one execution. `GET /api/workflows/{workflowId}/executions` and `GET /api/executions` return pages. Step history is `GET /api/executions/{id}/steps`. `leaseUntil` is informational: a time in the past does not prove the worker has stopped.
- `RECEIVED`, `QUEUED`, and `RUNNING` mean the execution has been accepted or is still in progress. They are not a completed workflow. `SUCCEEDED` and `FAILED` are finished. A failure includes `failureReason` when one was recorded.
- Health and info are exposed by Actuator. Backend and frontend tests run in GitHub Actions on pull requests and pushes to `main`.

## Tests

From `backend\`, run `.\mvnw.cmd test`. Those tests use their own database and broker. Do not point them at the local Postgres on port 5433 or RabbitMQ on 5672. From `frontend\`, run `npm test`, `npm run lint`, and `npm run build`.
