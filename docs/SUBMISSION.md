# Submission checklist

Everything the marker or a reviewer needs in one place: where the work lives, how to check it, how it maps to the
definition of done, and what is honestly not finished. Status is as read from the repository when this page was written;
items marked **owner to confirm** cannot be verified from the code.

## 1. Before you submit

- [ ] Branch to submit: `integration/sprint2-manual-test` (everything below is merged into it). Confirm its head with `git log --oneline -5`.
- [ ] Nothing secret committed: `git ls-files | grep -E '(^|/)\.env$'` prints nothing (`.env` is gitignored, `.env.example` is the template).
- [ ] `.env` filled from `.env.example`, including `STOCKER_INTERNAL_TOKEN` (required; `.env.example` still calls it optional).
- [ ] The crawl is off (`STOCKER_INGEST_ENABLED=false` in `.env`) unless the owner has approved a live run.
- [ ] `./gradlew test` is green on a clean checkout (offline; see section 4). **Owner to confirm** on the submission machine.
- [ ] `scripts/manual-test/stack-up.sh` then `scripts/manual-test/smoke.sh` run against Neon (needs the `shopping` and `notifications` databases). **Owner to confirm.**
- [ ] The team has checked the AUT policy on generative-AI assistance and made the declaration it requires (section 6).
- [ ] Decide about `docs/aotearoa-data-explorer-api.json` (section 5, last item).
- [ ] Push the branches the marker needs (section 2): per the local remote-tracking refs, only `feature/price-engine` (and the old pre-sprint feature branches) exist on `origin`.

## 2. Branch map

Workflow: `main` -> `dev` -> one `feature/*` branch per story. The sprint branches are **stacked** (each is built on the one before),
so review each one against its parent, not against `dev`. The "built on" column was derived with `git merge-base --is-ancestor`
(nearest ancestor branch); re-check with `git log --oneline --first-parent <parent>..<branch>` before relying on it. All branches below
are local; none is merged into `dev`.

| Branch | Built on | What it holds |
|---|---|---|
| `feature/price-engine` | `dev` | Price engine core: Playwright crawler (New World, PAK'nSAVE, Woolworths), `price_records` / `price_stats`, shopping list with price comparison, SSE push, speed rework (in-memory snapshot, never wait for a supermarket), acceptance tests, crawl zero-result quarantine. The only sprint branch also on `origin` |
| `feature/expiry-notifications` | `dev` | Pantry expiry date and threshold (inventory V2) and the `notifications` service (scan, list, acknowledge) |
| `feature/favorite-stores` | `dev` (merged with `feature/price-engine`) | Favorite stores (shopping V4), store product listing (`ChainCatalog.ListProducts`) |
| `feature/click-collect-costs` | `feature/price-engine` | Delivery / click-and-collect fees with per-island overrides in list comparison (`docs/features/fulfilment-costs.md`). Travel cost and the `in_store` method were removed. Intermediate branches `feature/click-collect-costs-*` are merged into it |
| `feature/search-relevance` | `feature/price-engine` | Price search v2: merged products with a price per chain, real alternatives, size/grade parsing, product selection, cancellation. Intermediate `feature/search-relevance-*` branches are merged into it |
| `feature/search-coverage-v3` | `feature/search-relevance` | `status` and `refresh` fields, generic fallback, replace-on-select, staple coverage, pagination driven by the "Showing A - B of M" line |
| `feature/pantry-custom-items` | `feature/click-collect-costs` | Free-text pantry items (inventory V3) |
| `feature/search-suggest` | `feature/pantry-custom-items` | `GET /api/pricing/suggest` search-bar suggestions |
| `feature/crawl-brake` | `feature/search-suggest` | 20-40 s crawl pace and the per-chain circuit breaker |
| `feature/crawl-parallel` | `feature/crawl-brake` | Optional parallel crawl of the three chains (one Firefox each), `docker-compose.crawl.yml` |
| `feature/crawl-template` | `feature/crawl-brake` | Per-chain page templates and optional lean page loads (default off) |
| `feature/crawl-incremental-save` | `feature/crawl-parallel` | Store rows after every category instead of at the end of the run |
| `feature/crawl-batch-persist` | `feature/crawl-incremental-save` | Batched persistence (fixed number of database round trips per category) and retry on dropped connections |
| `feature/woolworths-extractor` | `feature/crawl-parallel` | Woolworths tiles read before they were hydrated (wait for names, one WARN per page) |
| `feature/woolworths-pagination` | `feature/crawl-incremental-save` | Woolworths `?page=N` pagination, saved per category |
| `feature/final-fixes` | `feature/crawl-batch-persist` | Final-review fixes in the ingest (price parsing, page-walk end, lean default, transient database failures) |
| `feature/final-fixes-gateway` | `feature/crawl-batch-persist` | Gateway final-review security fixes (reject refresh tokens as Bearer tokens, require the internal token, map gRPC errors, CORS) and a Spring Framework 7 header fix |
| `feature/final-fixes-inventory` | `feature/crawl-batch-persist` | Pantry scoped to the gateway user (`X-User-Id`), 404 for foreign items, free-text expiry notifications |
| `feature/final-2a` | `integration/sprint2-manual-test` (at `8c8df87`) | One commit so far (`092330d`): notifications null `product_id` and scan resilience, compose ports / secrets / dockerignore hardening. Merged into integration (`187e910`) after this documentation branch was cut |
| `feature/final-2b` | `integration/sprint2-manual-test` (at `8c8df87`) | Its committed head equals that integration commit; its pricing crawl changes (breaker, page budget, pagination) were still uncommitted in its worktree when this page was written |
| `feature/final-docs` | `integration/sprint2-manual-test` | This documentation pass (README, AGENT.md files, runbook, this page) |
| `integration/sprint2-manual-test` | all of the above | The integrated, runnable state: compose, resource limits, `scripts/manual-test/`, `docs/MANUAL_TESTING.md` |

Intermediate branches that are fully merged and can be deleted: `feature/price-engine-reliability`, `feature/price-engine-acceptance-tests`,
`feature/crawl-zero-result-quarantine`, `feature/click-collect-costs-*`, `feature/search-relevance-*`.
`feature/final-2a` and `feature/final-2b` were being worked on when this page was written: check their heads, and that both are merged into integration, before submitting.

### How to review a branch

```bash
git log --oneline --first-parent <parent>..<branch>        # the commits this branch adds
git diff --stat <parent>...<branch>                          # what it touches
git worktree add ../Stocker-Backend-review <branch>          # a clean checkout, then run its module tests (section 3)
```

Read the feature document first (`docs/features/<feature>.md` or the module's `AGENT.md`), then the tests named after the acceptance criteria,
then the code. Sprint 2 feature branches reference the Trello acceptance tests in their test names; `docs/MANUAL_TESTING.md` section 4 walks each one by hand.

## 3. Test commands per module

Requires the JDK 25 toolchain. All commands are offline (gated tests are skipped; see `docs/testing.md` for the gate variables).

```bash
./gradlew test                              # all modules
./gradlew :gateway:test
./gradlew :services:identity:test
./gradlew :services:catalog:test
./gradlew :services:inventory:test
./gradlew :services:notifications:test
./gradlew :services:shopping:test
./gradlew :services:pricing:test
./gradlew :services:analytics:test
./gradlew serviceTest                       # service-scenario layer (@Tag("service")) across modules
./gradlew featureIntegrationTest            # one end-to-end test per feature (@Tag("feature-integration"))
scripts/manual-test/smoke.sh                # against the running stack, through the gateway only
```

## 4. Definition of done, mapped to the evidence

| Criterion | Where to look | Status |
|---|---|---|
| Acceptance criteria met | `docs/MANUAL_TESTING.md` section 4 (expiry notifications: 8 tests; favorite stores; list comparison; savings; fulfilment fees) and the `*ScenarioTest` / `*IntegrationTest` classes named after them | Automated tests exist; the manual run is **owner to confirm** |
| Builds on any machine | Gradle wrapper plus JDK 25 toolchain; Docker stack against Neon with `.env.example`; `docker-compose.limits.yml` (use `STACK_LIMITS=off` below 6 cores); `./gradlew test` needs no network, database or Kafka | Not re-run for this page (no Gradle, Docker or network was available). **Owner to confirm** |
| Acceptance tests | Test layers in `docs/testing.md`: unit, service scenario, feature integration, gated database and live tests; `services/pricing/src/test/.../acceptance` | Present |
| Peer tested | Another team member runs the manual checklist on the integration branch | **Owner to confirm** (not recorded in the repository) |
| Integrated | Every feature branch above is an ancestor of `integration/sprint2-manual-test`; features reach `dev` through pull requests | Integrated locally; PRs to `dev` **owner to confirm** |
| No major known bugs | Section 5 below lists the known limitations; none blocks the sprint stories | See section 5 |
| Documentation | `README.md`, `docs/MANUAL_TESTING.md`, `docs/testing.md`, `docs/features/*`, `docs/frontend/FRONTEND_PROMPT.md`, per-service `AGENT.md` | Updated in `feature/final-docs` |

## 5. Known limitations

- `price_records` grows on every crawl: it is append-only and there is no retention policy (Neon storage is capped).
- The crawled promo flag is always false: the crawler does not read promo badges, and crawled rows are built by `RawProductPriceRecordMapper`
  with `promoFlag(false)`. Promo information appears only through other paths.
- The default store is chosen by the supermarket site (IP-geolocated for the Foodstuffs sites), not by Stocker; prices are per chain, not per store.
- The pricing snapshot reloads every 15 s (`app.pricing.snapshot.refresh-interval`), so a running pricing service queries Neon constantly and
  keeps the compute from auto-suspending.
- Chain-level prices only: no branch, distance or travel features.
- NZ supermarket sites' terms of use restrict automated access (not stated anywhere in this repository; to be checked by the team). The crawl is
  therefore off by default, rate-limited (20-40 s per page, 140 pages per chain per day), protected by a per-chain circuit breaker, and must only be
  run with the owner's explicit approval.
- Woolworths pagination was verified live on one category only (biscuits-cookies).
- New World and PAK'nSAVE coverage is partial: the first runs hit HTTP 429 at the old 4-9 s pace; the current pace and breaker were not proven by a complete run.
- The service-area region list (auckland, wellington, christchurch) is a placeholder pending sign-off. Fee tables are owner-supplied estimates and do not model
  express delivery, surcharges or Club+.
- The ACC "Example of a weekly shopping list" source was not readable (bot protection), so its items are not in the common-product list
  (`docs/features/coverage-staples.md`). The common-product list is hand-curated, not an official Stats NZ list.
- Catalog writes (create, delete, import) are denied through the gateway because identity issues no admin role yet.
- The identity and catalog tables are managed in Neon by hand (Flyway is disabled for them in compose); identity's migrations do not create the
  `grocery_budget` / `spending_report` columns.
- Notifications are poll-only (no push). `analytics` and shopping's outbox publisher are stubs. The shopping SSE push is in memory (one gateway instance).
- `docs/aotearoa-data-explorer-api.json` is an unreferenced 125,283-byte Stats NZ API specification (`git grep -i aotearoa` finds only the file itself).
  **Keep or delete: the owner decides.**

## 6. Team and AI assistance disclosure

Stocker is a team project. On `integration/sprint2-manual-test` (counted when this page was written), 59 commits carry `Co-Authored-By: Claude Sonnet 5.5` and 7 carry
`Co-Authored-By: Claude Sonnet 5` (count with `git log --format=%b | grep -i '^Co-Authored-By' | sort | uniq -c`); an AI assistant (Claude Code) helped write
code, tests, scripts and documentation. The team should check the AUT policy on generative-AI use for this assessment and make the declaration
that policy requires; the trailers are the evidence trail.
