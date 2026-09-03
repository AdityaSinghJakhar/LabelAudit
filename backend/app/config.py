from pathlib import Path

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "Label Compliance Audit API"
    app_version: str = "0.1.0"
    database_url: str = "postgresql://labelaudit:labelaudit@localhost:5432/labelaudit"

    # Local stand-in for object storage until MinIO is available.
    storage_dir: Path = Path("storage")

    max_upload_bytes: int = 15 * 1024 * 1024
    allowed_content_types: tuple[str, ...] = ("image/jpeg", "image/png")

    class Config:
        env_file = ".env"


settings = Settings()
