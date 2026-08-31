# Label Audit

Product label compliance verification for retail inspection. An inspector
photographs a label; the system runs OCR, extracts the regulated fields, and
evaluates them against a set of versioned compliance rules.

## Repository layout

```
app/        Android client (Kotlin, Jetpack Compose)
backend/    FastAPI service (OCR, field extraction, rules engine)
```

## Running locally

Start the backend:

```
cd backend
.venv\Scripts\activate
uvicorn app.main:app --reload --port 8000
```

Point the Android app at it. With a device or emulator attached over adb:

```
adb reverse tcp:8000 tcp:8000
```

Then build and install as usual. To reach a backend on the LAN instead, pass
the address at build time:

```
gradlew assembleDebug -PapiBaseUrl=http://192.168.1.20:8000/
```

## Status

Phase 0 complete: the app reaches the backend and reports its health.
