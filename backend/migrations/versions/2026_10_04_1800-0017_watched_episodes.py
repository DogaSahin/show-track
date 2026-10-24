"""watched episodes, backfilled from progress

Revision ID: 0017
Revises: 0016
Create Date: 2026-10-04 18:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = '0017'
down_revision: Union[str, Sequence[str], None] = '0016'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'watched_episodes',
        sa.Column('user_media_id', sa.Uuid(), nullable=False),
        sa.Column('episode_id', sa.Uuid(), nullable=False),
        sa.Column('watched_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.ForeignKeyConstraint(
            ['episode_id'], ['episodes.id'], name=op.f('fk_watched_episodes_episode_id_episodes'), ondelete='CASCADE'
        ),
        sa.ForeignKeyConstraint(
            ['user_media_id'], ['user_media.id'], name=op.f('fk_watched_episodes_user_media_id_user_media'),
            ondelete='CASCADE',
        ),
        sa.PrimaryKeyConstraint('user_media_id', 'episode_id', name=op.f('pk_watched_episodes')),
    )
    op.create_index(op.f('ix_watched_episodes_episode_id'), 'watched_episodes', ['episode_id'], unique=False)

    # One-off backfill for titles whose episode list is already stored: every entry gets its first
    # `progress` aired episodes, in season and episode order. Later lists are backfilled as they
    # arrive (library.service.backfill_watched); this is the same rule, written as plain SQL
    # because a migration must not import application code.
    op.execute(
        """
        INSERT INTO watched_episodes (user_media_id, episode_id)
        SELECT um.id, first_aired.id
        FROM user_media um
        JOIN media m ON m.id = um.media_id
        JOIN LATERAL (
            SELECT e.id
            FROM episodes e
            WHERE e.media_id = um.media_id
              AND (e.air_date <= (now() AT TIME ZONE 'UTC')::date OR (e.air_date IS NULL AND m.status = 'finished'))
            ORDER BY e.season_number, e.number
            LIMIT um.progress
        ) first_aired ON true
        WHERE um.progress > 0 AND m.episodes_synced_at IS NOT NULL
        ON CONFLICT DO NOTHING
        """
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_watched_episodes_episode_id'), table_name='watched_episodes')
    op.drop_table('watched_episodes')
