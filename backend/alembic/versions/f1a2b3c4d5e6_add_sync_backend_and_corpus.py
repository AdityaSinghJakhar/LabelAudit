"""Add sync backend fields and corpus_scans table

Revision ID: f1a2b3c4d5e6
Revises: e8b7cfdb45c4
Create Date: 2026-09-06 07:00:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = 'f1a2b3c4d5e6'
down_revision: Union[str, Sequence[str], None] = 'e8b7cfdb45c4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # devices additions
    op.add_column('devices', sa.Column('passcode_salt', sa.String(), nullable=True))
    op.add_column('devices', sa.Column('token', sa.String(), nullable=True))
    op.add_column('devices', sa.Column('claimed_at', sa.DateTime(timezone=True), nullable=True))
    op.create_index(op.f('ix_devices_token'), 'devices', ['token'], unique=True)

    # skus additions
    op.add_column('skus', sa.Column('source', sa.String(), server_default='ENROLLED_FROM_SCAN', nullable=False))
    op.add_column('skus', sa.Column('manufacturer_address', sa.String(), nullable=True))
    op.add_column('skus', sa.Column('fssai_licence', sa.String(), nullable=True))
    op.add_column('skus', sa.Column('saved_at', sa.BigInteger(), server_default='0', nullable=False))

    # calibrations additions
    op.add_column('calibrations', sa.Column('at', sa.BigInteger(), server_default='0', nullable=False))

    # corpus_scans table
    op.create_table(
        'corpus_scans',
        sa.Column('id', sa.String(), nullable=False),
        sa.Column('device_id', sa.String(), nullable=True),
        sa.Column('image_id', sa.String(), nullable=False),
        sa.Column('verdict', sa.String(), nullable=False),
        sa.Column('ruleset_version', sa.String(), nullable=False),
        sa.Column('frames_used', sa.Integer(), nullable=False),
        sa.Column('frame_count', sa.Integer(), nullable=False),
        sa.Column('storage_path', sa.String(), nullable=False),
        sa.Column('scan_json', sa.JSON(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(['device_id'], ['devices.id']),
        sa.PrimaryKeyConstraint('id')
    )


def downgrade() -> None:
    op.drop_table('corpus_scans')
    op.drop_column('calibrations', 'at')
    op.drop_column('skus', 'saved_at')
    op.drop_column('skus', 'fssai_licence')
    op.drop_column('skus', 'manufacturer_address')
    op.drop_column('skus', 'source')
    op.drop_index(op.f('ix_devices_token'), table_name='devices')
    op.drop_column('devices', 'claimed_at')
    op.drop_column('devices', 'token')
    op.drop_column('devices', 'passcode_salt')
