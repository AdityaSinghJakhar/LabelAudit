import os
import logging
from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

from app.config import settings

logger = logging.getLogger(__name__)


class Base(DeclarativeBase):
    """Shared declarative base for every ORM model in db/models.py."""
    pass


def get_db_url() -> str:
    return os.environ.get("DATABASE_URL", settings.database_url)


def create_db_engine(url: str | None = None):
    db_url = url or get_db_url()
    try:
        connect_args = {"check_same_thread": False} if db_url.startswith("sqlite") else {}
        eng = create_engine(db_url, connect_args=connect_args)
        # Verify connection if external DB
        if not db_url.startswith("sqlite"):
            with eng.connect():
                pass
        return eng
    except Exception as e:
        logger.warning("Could not connect to %s: %s. Falling back to local SQLite.", db_url, e)
        fallback_url = "sqlite:///./labelaudit.db"
        return create_engine(fallback_url, connect_args={"check_same_thread": False})


engine = create_db_engine()
Base.metadata.create_all(bind=engine)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def get_db():
    """FastAPI dependency that yields a database session."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()