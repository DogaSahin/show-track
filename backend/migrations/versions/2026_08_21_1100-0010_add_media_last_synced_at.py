"""add media last_synced_at

Revision ID: 0010
Revises: 0009
Create Date: 2026-08-21 11:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '0010'
down_revision: Union[str, Sequence[str], None] = '0009'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Nullable with no server default on purpose: NULL means "never fetched", which the sync
    # job's due predicate treats as always due. Backfilling now() here would mark every
    # existing row fresh and idle it for a full tier interval after deploy.
    op.add_column('media', sa.Column('last_synced_at', sa.DateTime(timezone=True), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('media', 'last_synced_at')
