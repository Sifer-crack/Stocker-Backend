# Manual test kit

Scripts for the owner's manual end-to-end test of the whole Stocker stack. Full instructions, checklists and
troubleshooting: [`docs/MANUAL_TESTING.md`](../../docs/MANUAL_TESTING.md).

| Script | What it does |
|---|---|
| `preflight.sh` | Checks docker, compose config, `.env` variables (names only, values never printed) and Neon URLs. PASS/FAIL table. |
| `stack-up.sh` | Preflight, `docker compose up -d --build`, waits for every `/healthz`, prints the URLs. Heavy: builds 8 images. |
| `stack-down.sh` | Stops the containers (`--volumes` also drops the Kafka volume). Neon data is untouched. |
| `logs.sh <service>` | Last 200 log lines of a service (extra args go to `docker compose logs`, e.g. `-f`). |
| `smoke.sh` | Automated end-to-end check through the gateway only (`http://localhost:8080`). Exits non-zero on failure. |

`STOCKER_ENV_FILE=/path/to/file` points the scripts at a different env file (default: `.env` in the repo root).
