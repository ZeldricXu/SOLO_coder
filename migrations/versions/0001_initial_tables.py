"""initial_tables

Revision ID: 0001
Revises: 
Create Date: 2026-01-01 00:00:00.000000

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = '0001'
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        'entities',
        sa.Column('id', sa.String(64), primary_key=True),
        sa.Column('type', sa.String(100), nullable=False),
        sa.Column('status', sa.String(50), nullable=False, default='pending'),
        sa.Column('attributes', sa.JSON(), nullable=False, default=dict),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now(), onupdate=sa.func.now()),
        sa.Index('idx_entities_type', 'type'),
        sa.Index('idx_entities_status', 'status'),
    )

    op.create_table(
        'configs',
        sa.Column('config_id', sa.String(64), primary_key=True),
        sa.Column('namespace', sa.String(100), nullable=False),
        sa.Column('version', sa.Integer(), nullable=False, default=1),
        sa.Column('parameters', sa.JSON(), nullable=False, default=dict),
        sa.Column('enabled', sa.Boolean(), nullable=False, default=True),
        sa.Column('applied_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Index('idx_configs_namespace', 'namespace'),
        sa.UniqueConstraint('namespace', 'version', name='uq_configs_namespace_version'),
    )

    op.create_table(
        'runs',
        sa.Column('run_id', sa.String(64), primary_key=True),
        sa.Column('entity_id', sa.String(64), nullable=False),
        sa.Column('phase', sa.String(50), nullable=False, default='initializing'),
        sa.Column('progress', sa.Float(), nullable=False, default=0.0),
        sa.Column('started_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('completed_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('error_detail', sa.Text(), nullable=True),
        sa.Index('idx_runs_entity_id', 'entity_id'),
        sa.Index('idx_runs_phase', 'phase'),
        sa.ForeignKeyConstraint(['entity_id'], ['entities.id'], name='fk_runs_entity'),
    )

    op.create_table(
        'metrics_snapshots',
        sa.Column('snapshot_id', sa.String(64), primary_key=True),
        sa.Column('timestamp', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('metrics', sa.JSON(), nullable=False, default=dict),
        sa.Column('dimensions', sa.JSON(), nullable=False, default=dict),
        sa.Index('idx_metrics_snapshots_timestamp', 'timestamp'),
    )

    op.create_table(
        'file_metadata',
        sa.Column('file_id', sa.String(64), primary_key=True),
        sa.Column('original_name', sa.String(500), nullable=False),
        sa.Column('bucket', sa.String(100), nullable=False, default='default'),
        sa.Column('content_type', sa.String(200), nullable=True),
        sa.Column('size', sa.BigInteger(), nullable=False, default=0),
        sa.Column('md5_hash', sa.String(32), nullable=True),
        sa.Column('uploaded_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('archived', sa.Boolean(), nullable=False, default=False),
        sa.Index('idx_file_metadata_bucket', 'bucket'),
        sa.Index('idx_file_metadata_uploaded_at', 'uploaded_at'),
        sa.Index('idx_file_metadata_expires_at', 'expires_at'),
    )

    op.create_table(
        'events',
        sa.Column('event_id', sa.String(64), primary_key=True),
        sa.Column('event_type', sa.String(100), nullable=False),
        sa.Column('source', sa.String(200), nullable=True),
        sa.Column('payload', sa.JSON(), nullable=False, default=dict),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('trace_id', sa.String(64), nullable=True),
        sa.Index('idx_events_event_type', 'event_type'),
        sa.Index('idx_events_created_at', 'created_at'),
        sa.Index('idx_events_trace_id', 'trace_id'),
    )

    op.create_table(
        'quality_reports',
        sa.Column('report_id', sa.String(64), primary_key=True),
        sa.Column('project_path', sa.String(1000), nullable=False),
        sa.Column('total_issues', sa.Integer(), nullable=False, default=0),
        sa.Column('passed', sa.Boolean(), nullable=False, default=False),
        sa.Column('score', sa.Float(), nullable=False, default=0.0),
        sa.Column('report_data', sa.JSON(), nullable=False, default=dict),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Index('idx_quality_reports_created_at', 'created_at'),
    )

    op.create_table(
        'vulnerability_reports',
        sa.Column('report_id', sa.String(64), primary_key=True),
        sa.Column('sbom_source', sa.String(1000), nullable=False),
        sa.Column('total_components', sa.Integer(), nullable=False, default=0),
        sa.Column('vulnerable_components', sa.Integer(), nullable=False, default=0),
        sa.Column('total_vulnerabilities', sa.Integer(), nullable=False, default=0),
        sa.Column('report_data', sa.JSON(), nullable=False, default=dict),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Index('idx_vulnerability_reports_created_at', 'created_at'),
    )


def downgrade() -> None:
    op.drop_table('vulnerability_reports')
    op.drop_table('quality_reports')
    op.drop_table('events')
    op.drop_table('file_metadata')
    op.drop_table('metrics_snapshots')
    op.drop_table('runs')
    op.drop_table('configs')
    op.drop_table('entities')
