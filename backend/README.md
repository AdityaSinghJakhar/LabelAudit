# Backend — Label Compliance Audit API

FastAPI service for the label compliance scanning pipeline.

## Requirements

Python 3.11 (PaddleOCR does not support 3.13+).

## Setup

```
py -3.11 -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

For running tests without the full PaddleOCR stack (see
requirements-dev.txt for why that's usually what you want during
development):

```
pip install -r requirements-dev.txt -e ../labelguard
```

## Run

```
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Interactive API docs: http://localhost:8000/docs

To let an attached Android device reach the server:

```
adb reverse tcp:8000 tcp:8000
```

Minimal working config for local development: only `DATABASE_SHARD_URLS`
(or `DATABASE_URL`) needs pointing at a real database; `AUTH_SECRET_KEY`
has an insecure dev default so the app boots without an `.env` file, but
**must** be overridden before any deployment reachable by anyone else.

## Tests

```
pip install -r requirements-dev.txt -e ../labelguard
pytest
```

Runs against a throwaway file-based SQLite database (see
`tests/conftest.py`) with `app.services.ocr_service.extract_text` stubbed
(see the `stub_ocr` fixture) -- no PaddleOCR install, GPU, or real image
needed to run the suite. 87 tests as of this writing, covering:

- pure-function unit tests (field extraction, spatial extraction,
  normalization, registry matching, rule evaluation)
- integration tests that drive the real FastAPI app end-to-end through
  `TestClient`, including actual SQLite persistence via the shard router
  -- this is what catches wiring bugs between layers, not just bugs
  within one function

## Endpoints

| Method | Path                  | Purpose                                             |
|--------|-----------------------|------------------------------------------------------|
| GET    | `/`                   | Service info                                         |
| GET    | `/api/health`         | Liveness check                                       |
| POST   | `/api/scans`          | Submit an image for server-side OCR + rule evaluation |
| GET    | `/api/scans`          | A device's scan history (`?device_id=...`)           |
| GET    | `/api/scans/{scan_id}`| One scan's full stored detail (`?device_id=...`)     |
| POST   | `/api/skus`           | Register a reference SKU                             |
| GET    | `/api/skus`           | List registered SKUs                                 |
| GET    | `/api/skus/{sku_id}`  | One registered SKU                                   |
| PATCH  | `/api/skus/{sku_id}`  | Update a registered SKU                              |
| DELETE | `/api/skus/{sku_id}`  | Remove a registered SKU                              |

`device_id` is required on the scan read endpoints because it is the
shard key (see `db/sharding.py`) -- it is what selects which database a
scan actually lives on, not an optional filter.

## Architecture notes worth knowing before changing anything

- **Everything in this backend's request path is synchronous.**
  `db/sharding.py` builds plain `sqlalchemy.orm.Session` objects, not
  `AsyncSession`. `app/services/registry_matcher.py` and
  `app/services/scan_pipeline.py` are written against that; keep it that
  way unless the shard router itself moves to an async engine.
- **`app/services/matcher.py` is not wired into the live pipeline.** It's
  a separate, independently-tested Hungarian matcher kept as a candidate
  for later; see its module docstring.
- **`labelguard.rules.engine` is an intentional empty stub.**
  `app/services/rules_service.py` implements a deliberately small,
  clearly-scoped subset of check types itself (`field_present`,
  `matches_registry`) and reports everything else as `NOT_ASSESSABLE`
  rather than guessing -- see that module's docstring for exactly what's
  covered and why. This mirrors the on-device app's own governing rule
  (HANDOFF.md): never emit a verdict the pipeline cannot substantiate.

