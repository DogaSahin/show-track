"""add push targets

Revision ID: 0011
Revises: 0010
Create Date: 2026-08-21 12:31:39.914299

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '0011'
down_revision: Union[str, Sequence[str], None] = '0010'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'push_targets',
        sa.Column('id', sa.Uuid(), server_default=sa.text('gen_random_uuid()'), nullable=False),
        sa.Column('user_id', sa.Uuid(), nullable=False),
        sa.Column('transport', sa.String(length=32), nullable=False),
        sa.Column('target', sa.String(length=255), nullable=False),
        sa.Column('label', sa.String(length=64), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('last_seen_at', sa.DateTime(timezone=True), nullable=True),
        # BARE "transport", not "ck_push_targets_transport": a named CheckConstraint attached
        # inside op.create_table is subject to the same naming-convention re-application as
        # op.drop_constraint/op.create_check_constraint below — the qualified form renders as
        # `ck_push_targets_ck_push_targets_transport`. Measured (same trap, a second place it bites).
        sa.CheckConstraint("transport IN ('ntfy')", name='transport'),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('transport', 'target', name='uq_push_targets_transport_target'),
    )
    op.create_index(op.f('ix_push_targets_user_id'), 'push_targets', ['user_id'])

    op.add_column('notification_tasks', sa.Column('next_attempt_at', sa.DateTime(timezone=True), nullable=True))

    # VARCHAR + CHECK, so this is a transactional drop-and-recreate and downgrade() genuinely
    # reverses it. None of the native-enum hazards apply.
    # BARE "status", not "ck_notification_tasks_status": op.drop_constraint RE-APPLIES the
    # metadata naming convention to whatever name you pass, so the qualified form renders as
    # `ck_notification_tasks_ck_notification_tasks_status` and the migration aborts on its first
    # statement. Measured (same trap 0009 already hit and documented for `threshold`).
    op.drop_constraint('status', 'notification_tasks', type_='check')
    op.create_check_constraint(
        'status',
        'notification_tasks',
        "status IN ('pending', 'sent', 'failed', 'skipped', 'expired')",
    )

    # Never written, never read; superseded by push_targets (6-N).
    op.drop_column('users', 'fcm_token')


def downgrade() -> None:
    """Downgrade schema."""
    op.add_column('users', sa.Column('fcm_token', sa.String(length=255), nullable=True))

    op.drop_constraint('status', 'notification_tasks', type_='check')
    op.create_check_constraint(
        'status',
        'notification_tasks',
        "status IN ('pending', 'sent', 'failed')",
    )

    op.drop_column('notification_tasks', 'next_attempt_at')

    op.drop_index(op.f('ix_push_targets_user_id'), table_name='push_targets')
    op.drop_table('push_targets')
