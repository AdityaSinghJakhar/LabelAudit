import os
import sys
import tempfile
from pathlib import Path

_TMP_DIR = tempfile.mkdtemp(prefix="labelguard_backend_tests_")
_DB_PATH = Path(_TMP_DIR) / "test_db.sqlite"

os.environ["DATABASE_URL"] = f"sqlite:///{_DB_PATH}"
os.environ["STORAGE_DIR"] = str(Path(_TMP_DIR) / "storage")

# Clear any cached imports
for module_name in list(sys.modules):
    if module_name in ("app", "db") or module_name.startswith(("app.", "db.")):
        del sys.modules[module_name]

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.config import settings
from app.main import app
from db import models
from db.session import Base, get_db

test_engine = create_engine(
    f"sqlite:///{_DB_PATH}",
    connect_args={"check_same_thread": False},
)
TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=test_engine)


@pytest.fixture(autouse=True)
def clean_database():
    """Fresh schema for every test."""
    Base.metadata.drop_all(bind=test_engine)
    Base.metadata.create_all(bind=test_engine)
    yield
    Base.metadata.drop_all(bind=test_engine)


@pytest.fixture
def db_session():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


@pytest.fixture
def client(db_session):
    def override_get_db():
        try:
            yield db_session
        finally:
            pass

    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()
