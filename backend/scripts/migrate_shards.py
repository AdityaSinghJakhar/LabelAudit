"""
Run `alembic upgrade head` against every shard in DATABASE_SHARD_URLS.

Alembic's config file only knows about one `sqlalchemy.url`. Rather than
hand-edit alembic.ini per shard, this overrides that value in-process for
each shard URL in turn and invokes the same migrations against each one.
Every shard has an identical schema -- sharding here splits *rows* by
device_id, not *schema* -- so running the same migration set N times, once
per shard, is correct and is the whole story.

Usage:
    cd backend
    python -m scripts.migrate_shards
"""

from __future__ import annotations

import sys
from pathlib import Path

from alembic import command
from alembic.config import Config

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import settings  # noqa: E402


def main() -> None:
    shard_urls = settings.db_shard_urls
    print(f"Migrating {len(shard_urls)} shard(s):")

    backend_dir = Path(__file__).resolve().parent.parent
    for index, url in enumerate(shard_urls):
        redacted = url.split("@")[-1]  # don't print credentials
        print(f"  [{index}] ...@{redacted}")

        cfg = Config(str(backend_dir / "alembic.ini"))
        cfg.set_main_option("script_location", str(backend_dir / "alembic"))
        cfg.set_main_option("sqlalchemy.url", url)
        command.upgrade(cfg, "head")

    print("All shards migrated.")


if __name__ == "__main__":
    main()
