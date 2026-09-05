"""add server-ocr columns to scans

Revision ID: 1a1a94272376
Revises: 47e0afd9857d
Create Date: 2026-09-06 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "1a1a94272376"
down_revision: Union[str, Sequence[str], None] = "47e0afd9857d"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "scans",
        sa.Column("source", sa.String(), nullable=False, server_default="device"),
    )
    op.add_column("scans", sa.Column("image_key", sa.String(), nullable=True))
    op.add_column("scans", sa.Column("ocr_model", sa.String(), nullable=True))
    op.add_column(
        "scans", sa.Column("ocr_mean_confidence", sa.Float(), nullable=True)
    )
    op.add_column(
        "scans", sa.Column("ocr_processing_time_ms", sa.Integer(), nullable=True)
    )
    op.add_column("scans", sa.Column("shard_index", sa.Integer(), nullable=True))
    op.create_index(op.f("ix_scans_source"), "scans", ["source"], unique=False)


def downgrade() -> None:
    op.drop_index(op.f("ix_scans_source"), table_name="scans")
    op.drop_column("scans", "shard_index")
    op.drop_column("scans", "ocr_processing_time_ms")
    op.drop_column("scans", "ocr_mean_confidence")
    op.drop_column("scans", "ocr_model")
    op.drop_column("scans", "image_key")
    op.drop_column("scans", "source")
