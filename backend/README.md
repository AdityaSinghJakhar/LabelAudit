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

## Run

```
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Interactive API docs: http://localhost:8000/docs

To let an attached Android device reach the server:

```
adb reverse tcp:8000 tcp:8000
```

## Endpoints

| Method | Path          | Purpose        |
|--------|---------------|----------------|
| GET    | `/`           | Service info   |
| GET    | `/api/health` | Liveness check |
