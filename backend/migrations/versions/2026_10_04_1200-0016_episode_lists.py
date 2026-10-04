"""episode lists: episode titles, and when a title's list was fetched

Revision ID: 0016
Revises: 0015
Create Date: 2026-10-04 12:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = '0016'
down_revision: Union[str, Sequence[str], None] = '0015'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # All nullable, no backfill: NULL is the honest value for every existing row ("never
    # fetched"), and the sync job fills them in from there.
    op.add_column('episodes', sa.Column('title', sa.Text(), nullable=True))
    op.add_column('media', sa.Column('episodes_synced_at', sa.DateTime(timezone=True), nullable=True))
    op.add_column('media', sa.Column('total_episodes', sa.Integer(), nullable=True))
    op.add_column(
        'media',
        sa.Column('episodes_refresh_due', sa.Boolean(), server_default=sa.text('false'), nullable=False),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('media', 'episodes_refresh_due')
    op.drop_column('media', 'total_episodes')
    op.drop_column('media', 'episodes_synced_at')
    op.drop_column('episodes', 'title')
