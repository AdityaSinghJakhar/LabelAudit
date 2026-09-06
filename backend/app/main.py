from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api import (
    calibrations,
    conflicts,
    corpus,
    devices,
    health,
    registry,
    reports,
    scan,
)
from app.config import settings
from db.models import Base
from db.session import engine


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Ensure database tables exist on startup
    Base.metadata.create_all(bind=engine)
    yield


app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    lifespan=lifespan,
)

# Mount sync backend routers
app.include_router(health.router, prefix="/api")
app.include_router(devices.router, prefix="/api")
app.include_router(scan.router, prefix="/api")
app.include_router(conflicts.router, prefix="/api")
app.include_router(registry.router, prefix="/api")
app.include_router(calibrations.router, prefix="/api")
app.include_router(reports.router, prefix="/api")
app.include_router(corpus.router, prefix="/api")


@app.get("/")
def root():
    return {
        "service": settings.app_name,
        "version": settings.app_version,
        "mode": "sync_backend",
    }
