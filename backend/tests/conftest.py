"""
Shared pytest fixtures for the backend test suite.

DATABASE_SHARD_URLS / AUTH_SECRET_KEY are set in os.environ at *collection*
time, before any `app.*` or `db.*` module is imported anywhere in the test
run. This matters because db/sharding.py builds its ShardRouter (and
therefore its SQLAlchemy engines) once, at import time, from
settings.db_shard_urls -- there is no later hook to repoint it at a test
database. Setting the env var in this file's top-level code (which pytest
executes before collecting any test module) is what makes that import-time
construction land on a throwaway file-based SQLite database instead of the
Postgres URL config.py defaults to.

A file-based SQLite database (not :memory:) is used because the shard
router's engines use SQLAlchemy's default connection pooling: an
in-memory SQLite database is scoped to a single connection, so a second
session from the same "shard" would see an empty, unrelated database.
A temp file, shared by every connection made against connection.
"""

import os
import sys
import tempfile
from pathlib import Path

_TMP_DIR = tempfile.mkdtemp(prefix="labelguard_backend_tests_")
_DB_PATH = Path(_TMP_DIR) / "test_shard_0.db"

os.environ.setdefault("DATABASE_SHARD_URLS", f"sqlite:///{_DB_PATH}")
os.environ.setdefault("AUTH_SECRET_KEY", "test-secret-not-for-production")
os.environ.setdefault("STORAGE_DIR", str(Path(_TMP_DIR) / "storage"))

# Make sure a stray already-imported `app`/`db` package from an earlier,
# differently-configured process can't shadow the env vars above.
for module_name in list(sys.modules):
    if module_name == "app" or module_name.startswith("app.") \
            or module_name == "db" or module_name.startswith("db."):
        del sys.modules[module_name]

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402
from app.models.ocr import BoundingBox, OcrResult, OcrToken  # noqa: E402
from app.services import ocr_service  # noqa: E402
from db import models  # noqa: E402
from db.session import Base  # noqa: E402
from db.sharding import router as shard_router  # noqa: E402


# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _clean_database():
    """
    Fresh schema for every test.

    Recreating all tables per test (rather than wrapping each test in a
    rolled-back transaction) is the simplest thing that works correctly
    across every engine the shard router owns, and the suite is small
    enough that the extra DDL cost per test is not worth the added
    complexity of nested-transaction fixtures.
    """

    for engine in shard_router.all_engines():
        Base.metadata.drop_all(engine)
        Base.metadata.create_all(engine)

    yield

    for engine in shard_router.all_engines():
        Base.metadata.drop_all(engine)


@pytest.fixture
def db_session():
    """
    A raw session against shard 0, for tests that want to set up rows
    (e.g. a registered Sku) directly rather than through the API.
    """

    session, _shard_index = shard_router.session_for_key("conftest-fixture")

    try:
        yield session
    finally:
        session.close()


# ---------------------------------------------------------------------------
# HTTP client
# ---------------------------------------------------------------------------


@pytest.fixture
def client():
    return TestClient(app)


# ---------------------------------------------------------------------------
# OCR stubbing
# ---------------------------------------------------------------------------


def make_ocr_result(
    lines: list[str],
    *,
    confidence: float = 0.95,
    model: str = "stub-ocr",
) -> OcrResult:
    """
    Build an OcrResult from plain text lines, one OCR token per line,
    stacked top-to-bottom with a plausible bounding box each. Good enough
    for rules_service (which only reads full_text and mean_confidence)
    and for spatial_graph_extractor (which needs distinct x/y per line so
    tokens don't all collapse onto the same point).
    """

    tokens = []
    y = 0

    for line in lines:
        width = max(10, len(line) * 8)
        tokens.append(
            OcrToken(
                text=line,
                confidence=confidence,
                bbox=BoundingBox(x0=0, y0=y, x1=width, y1=y + 20),
            )
        )
        y += 30

    return OcrResult(
        tokens=tokens,
        full_text=" ".join(lines),
        processing_time_ms=1,
        model=model,
    )


@pytest.fixture
def stub_ocr(monkeypatch):
    """
    Replace ocr_service.extract_text with a fixture the test controls, so
    the suite never needs PaddleOCR installed or a real image to read.

    Usage:
        def test_x(client, stub_ocr):
            stub_ocr(["MRP Rs. 45.00", "Net Quantity 500 g"])
            ... post an image, assert on the response ...
    """

    def _install(lines: list[str], **kwargs) -> OcrResult:
        result = make_ocr_result(lines, **kwargs)
        monkeypatch.setattr(ocr_service, "extract_text", lambda data: result)
        return result

    return _install


# ---------------------------------------------------------------------------
# SKU registry factory
# ---------------------------------------------------------------------------


@pytest.fixture
def make_sku(db_session):
    """
    Insert a Sku row and commit it, returning the row. Session-scoped
    to db_session so it lands on the same shard/session a test's other
    setup uses; callers needing it visible to a request made through
    `client` must commit (this fixture does) since the API opens its own
    session per request.
    """

    created = []

    def _make(
        *,
        sku_id: str = "SKU-TEST-1",
        authority: str = "AUTHORITATIVE",
        brand_strings: list[str] | None = None,
        mrp_exact: float | None = 45.00,
        net_quantity: str | None = "500 g",
    ) -> models.Sku:
        sku = models.Sku(
            sku_id=sku_id,
            authority=authority,
            brand_strings=brand_strings if brand_strings is not None else ["acme"],
            mrp_exact=mrp_exact,
            net_quantity=net_quantity,
        )
        db_session.add(sku)
        db_session.commit()
        db_session.refresh(sku)
        created.append(sku)
        return sku

    return _make


# ---------------------------------------------------------------------------
# Sample images
# ---------------------------------------------------------------------------


@pytest.fixture
def sample_jpeg_bytes() -> bytes:
    import io

    from PIL import Image

    image = Image.new("RGB", (200, 200), color="white")
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG")
    return buffer.getvalue()
