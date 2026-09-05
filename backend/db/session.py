from collections.abc import Generator

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from app.config import settings

engine = create_engine(settings.database_url, pool_pre_ping=True)

SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)


class Base(DeclarativeBase):
    """Shared declarative base for every ORM model in app/db/models.py."""


def get_db() -> Generator[Session, None, None]:
    """
    FastAPI dependency. Yields one session per request and always closes it,
    including when the request handler raises.
    """
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()