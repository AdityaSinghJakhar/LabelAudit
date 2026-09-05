"""
Horizontal sharding across N independent Postgres databases.

WHY DEVICE_ID IS THE SHARD KEY
-------------------------------
Every write this backend does (Device, Calibration, Scan, ScanCheck) hangs
off a device. Almost every read an inspector does ("this phone's history",
"this phone's calibration") is also scoped to one device. That makes
device_id the natural partition key: a device's whole row-set lives on
exactly one shard, so the common queries never need to fan out across
shards. The only cross-device queries are the analytics/statistics-dashboard
ones (Section 5.3 of the implementation plan) -- those are explicitly
out of scope here and will need a fan-out/aggregation layer on top of this
router, not a rewrite of it.

HOW ASSIGNMENT WORKS
---------------------
shard_index = sha256(device_id) % shard_count

This is deterministic and needs no directory service: given the same
device_id and the same shard_count, every process (every uvicorn worker,
every background job) computes the same shard without asking anything.
sha256 (not Python's built-in hash()) because hash() is salted per-process
for security reasons -- it would assign the same device to a different
shard every time the process restarts, which is exactly the bug a shard
router exists to prevent.

KNOWN LIMITATION -- RESHARDING
--------------------------------
Changing shard_count re-hashes every device_id to a new shard, which is a
real migration (dump + copy + verify per device), not something this
router does for you. This is the standard weakness of hash-based sharding
versus consistent hashing / directory-based sharding. For a single-SKU SIH
prototype this is an acceptable, explicitly-stated limitation rather than a
silent one -- state it to judges, don't hide it. If the team outgrows this,
the fix is a `shard_map` table on a small always-on coordinator database
recording device_id -> shard_index once, at first sight, so growing the
shard count only affects new devices.
"""

from __future__ import annotations

import hashlib
import logging

from sqlalchemy import create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker

from app.config import settings

logger = logging.getLogger(__name__)


class ShardRouter:
    def __init__(self, shard_urls: list[str]) -> None:
        if not shard_urls:
            raise ValueError(
                "No shard URLs configured. Set DATABASE_SHARD_URLS "
                "(comma-separated) or DATABASE_URL in .env."
            )

        self.shard_urls = shard_urls
        self.engines: list[Engine] = [
            create_engine(url, pool_pre_ping=True) for url in shard_urls
        ]
        self.session_factories: list[sessionmaker[Session]] = [
            sessionmaker(bind=engine, autoflush=False, autocommit=False)
            for engine in self.engines
        ]
        logger.info("ShardRouter initialised with %d shard(s)", len(self.engines))

    @property
    def shard_count(self) -> int:
        return len(self.engines)

    def shard_index_for(self, shard_key: str) -> int:
        digest = hashlib.sha256(shard_key.encode("utf-8")).hexdigest()
        return int(digest, 16) % self.shard_count

    def engine_for_index(self, shard_index: int) -> Engine:
        return self.engines[shard_index]

    def session_for_key(self, shard_key: str) -> tuple[Session, int]:
        """Open a new session bound to the shard `shard_key` hashes to."""
        shard_index = self.shard_index_for(shard_key)
        session = self.session_factories[shard_index]()
        return session, shard_index

    def all_engines(self) -> list[Engine]:
        """Used by migration tooling to iterate every shard."""
        return self.engines


router = ShardRouter(settings.db_shard_urls)


def get_db_for_device(device_id: str):
    """
    FastAPI dependency factory. Since the shard depends on a path/body
    parameter (device_id), this returns a dependency function closed over
    that id rather than being a plain dependency itself -- call it from the
    route as `Depends(get_db_for_device(device_id))`.
    """

    def _dep():
        session, shard_index = router.session_for_key(device_id)
        try:
            yield session, shard_index
        finally:
            session.close()

    return _dep
