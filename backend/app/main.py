import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api import health, scan
from app.config import settings
from app.services import ocr_service


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Load OCR models in the background so startup is not blocked but the
    # first scan does not pay the initialisation cost either.
    warm_up = asyncio.create_task(asyncio.to_thread(ocr_service.warm_up))
    yield
    warm_up.cancel()


app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    lifespan=lifespan,
)

app.include_router(health.router, prefix="/api")
app.include_router(scan.router, prefix="/api")


@app.get("/")
def root():
    return {"service": settings.app_name, "version": settings.app_version}
