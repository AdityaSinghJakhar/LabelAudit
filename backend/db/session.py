"""
Holds only the shared declarative Base now.

Before sharding, this module also built the single global engine/session
every request used. That's gone -- db/sharding.py's ShardRouter builds one
engine per configured shard instead, and every request path goes through
it (see app/api/scan.py's `_shard_session` dependency). Keeping a second,
unused engine here pointed at settings.database_url was worse than
useless: it was built eagerly at import time, so it forced whichever DB
driver settings.database_url implied (psycopg2, by default) to be
importable even in a deployment that only ever talks to the configured
shards -- a real footgun for anyone running a lighter dev setup (e.g.
sqlite shards for a quick local smoke test) without also installing a
driver for a database nothing actually connects to.

Base stays here (not moved into sharding.py) because alembic/env.py and
db/models.py both import it, and neither needs to know sharding exists.
"""

from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    """Shared declarative base for every ORM model in db/models.py."""