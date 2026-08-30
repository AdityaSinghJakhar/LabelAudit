from fastapi import FastAPI

from app.api import health
from app.config import settings

app = FastAPI(title=settings.app_name, version=settings.app_version)

app.include_router(health.router, prefix="/api")


@app.get("/")
def root():
    return {"service": settings.app_name, "version": settings.app_version}
