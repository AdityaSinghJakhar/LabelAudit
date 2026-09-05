from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "Label Compliance Audit API"
    app_version: str = "0.1.0"

    # Legacy single-database URL. Still used as the fallback shard when
    # DATABASE_SHARD_URLS is not set, so a solo dev running one local
    # Postgres doesn't need to configure sharding just to boot the app.
    database_url: str = "postgresql://labelaudit:labelaudit@localhost:5432/labelaudit"

    # Comma-separated list of Postgres URLs, one per shard, e.g.:
    #   DATABASE_SHARD_URLS=postgresql://.../labelaudit_0,postgresql://.../labelaudit_1
    # Each must be migrated independently -- see scripts/migrate_shards.py.
    database_shard_urls: str = ""

    auth_secret_key: str

    storage_dir: Path = Path("storage")
    max_upload_bytes: int = 15 * 1024 * 1024
    allowed_content_types: tuple[str, ...] = ("image/jpeg", "image/png")

    # Below this mean OCR token confidence, presence checks are reported
    # NOT_ASSESSABLE instead of FAIL -- a blurry photo must not manufacture
    # a false "missing declaration" finding. See rules_service.py.
    min_ocr_confidence_for_verdict: float = 0.55

    model_config = SettingsConfigDict(
        env_file=".env",
        extra="ignore",
    )

    @property
    def db_shard_urls(self) -> list[str]:
        raw = self.database_shard_urls.strip()
        if not raw:
            return [self.database_url]
        return [url.strip() for url in raw.split(",") if url.strip()]


settings = Settings()