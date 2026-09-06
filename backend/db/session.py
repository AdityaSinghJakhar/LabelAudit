import os
from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

from app.config import settings


class Base(DeclarativeBase):
    """Shared declarative base for every ORM model in db/models.py."""
    pass


def get_db_url() -> str:
    # Prioritize DATABASE_URL, fallback to sqlite for local testing/offline run
    return os.environ.get("DATABASE_URL", settings.database_url)


def create_db_engine(url: str | None = None):
    db_url = url or get_db_url()
    connect_args = {"check_same_thread": False} if db_url.startswith("sqlite") else {}
    return create_engine(db_url, connect_args=connect_args)


engine = create_db_engine()
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def get_db():
    """FastAPI dependency that yields a database session."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()