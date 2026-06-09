"""Initial schema - create all tables

Revision ID: 0001
Revises:
Create Date: 2026-06-09 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision: str = '0001'
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'extraction_schemas',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('schema_name', sa.String(length=256), nullable=False, index=True),
        sa.Column('schema_version', sa.String(length=64), nullable=False, server_default='1.0'),
        sa.Column('description', sa.Text(), nullable=True),
        sa.Column('business_line', sa.String(length=128), nullable=True, index=True),
        sa.Column('document_types', sa.JSON(), nullable=True),
        sa.Column('fields', sa.JSON(), nullable=False),
        sa.Column('is_active', sa.Boolean(), nullable=True, server_default='true', index=True),
        sa.Column('is_default', sa.Boolean(), nullable=True, server_default='false', index=True),
        sa.Column('created_by', sa.String(length=256), nullable=True),
        sa.Column('yaml_source_path', sa.String(length=1024), nullable=True),
        sa.Column('yaml_content', sa.Text(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('schema_name')
    )

    op.create_table(
        'model_versions',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('model_name', sa.String(length=256), nullable=False, index=True),
        sa.Column('model_type', sa.Enum('EXTRACTION', 'LAYOUT_ANALYSIS', 'TABLE_DETECTION', 'OCR', 'MULTIMODAL', name='modeltype'), nullable=False, index=True),
        sa.Column('version', sa.String(length=64), nullable=False, index=True),
        sa.Column('description', sa.Text(), nullable=True),
        sa.Column('status', sa.Enum('DRAFT', 'TESTING', 'STAGING', 'PRODUCTION', 'ARCHIVED', name='modelstatus'), nullable=True, server_default='DRAFT', index=True),
        sa.Column('is_default', sa.Boolean(), nullable=True, server_default='false', index=True),
        sa.Column('minio_bucket', sa.String(length=256), nullable=True),
        sa.Column('minio_path', sa.String(length=1024), nullable=True),
        sa.Column('local_path', sa.String(length=1024), nullable=True),
        sa.Column('architecture', sa.String(length=256), nullable=True),
        sa.Column('framework', sa.String(length=128), nullable=True),
        sa.Column('framework_version', sa.String(length=64), nullable=True),
        sa.Column('training_dataset', sa.String(length=1024), nullable=True),
        sa.Column('training_start_date', sa.DateTime(), nullable=True),
        sa.Column('training_end_date', sa.DateTime(), nullable=True),
        sa.Column('training_duration_hours', sa.Float(), nullable=True),
        sa.Column('metrics', sa.JSON(), nullable=True),
        sa.Column('validation_metrics', sa.JSON(), nullable=True),
        sa.Column('test_metrics', sa.JSON(), nullable=True),
        sa.Column('requirements', sa.JSON(), nullable=True),
        sa.Column('hardware_requirements', sa.JSON(), nullable=True),
        sa.Column('deployed_at', sa.DateTime(), nullable=True),
        sa.Column('deployed_by', sa.String(length=256), nullable=True),
        sa.Column('deployment_config', sa.JSON(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id')
    )

    op.create_table(
        'ab_test_experiments',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('experiment_name', sa.String(length=256), nullable=False, index=True),
        sa.Column('description', sa.Text(), nullable=True),
        sa.Column('model_name', sa.String(length=256), nullable=False, index=True),
        sa.Column('variant_a_model_id', sa.Integer(), nullable=False, index=True),
        sa.Column('variant_b_model_id', sa.Integer(), nullable=False, index=True),
        sa.Column('traffic_split_a', sa.Float(), nullable=True, server_default='50.0'),
        sa.Column('traffic_split_b', sa.Float(), nullable=True, server_default='50.0'),
        sa.Column('strategy', sa.String(length=64), nullable=True, server_default='random'),
        sa.Column('primary_metric', sa.String(length=128), nullable=True, server_default='accuracy'),
        sa.Column('status', sa.String(length=64), nullable=True, server_default='draft', index=True),
        sa.Column('is_active', sa.Boolean(), nullable=True, server_default='false', index=True),
        sa.Column('sample_size_a', sa.Integer(), nullable=True, server_default='0'),
        sa.Column('sample_size_b', sa.Integer(), nullable=True, server_default='0'),
        sa.Column('target_metrics', sa.JSON(), nullable=True),
        sa.Column('start_date', sa.DateTime(), nullable=True),
        sa.Column('end_date', sa.DateTime(), nullable=True),
        sa.Column('started_at', sa.DateTime(), nullable=True),
        sa.Column('ended_at', sa.DateTime(), nullable=True),
        sa.Column('confidence_level', sa.Float(), nullable=True, server_default='0.95'),
        sa.Column('created_by', sa.String(length=256), nullable=True),
        sa.Column('approved_by', sa.String(length=256), nullable=True),
        sa.Column('approved_at', sa.DateTime(), nullable=True),
        sa.Column('results_summary', sa.JSON(), nullable=True),
        sa.Column('winner', sa.String(length=64), nullable=True),
        sa.Column('winner_model_id', sa.Integer(), nullable=True),
        sa.Column('stopped_at', sa.DateTime(), nullable=True),
        sa.Column('stopped_reason', sa.Text(), nullable=True),
        sa.Column('notes', sa.Text(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id')
    )

    op.create_table(
        'ab_test_results',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('experiment_id', sa.Integer(), nullable=False, index=True),
        sa.Column('variant', sa.String(length=64), nullable=False, index=True),
        sa.Column('document_id', sa.Integer(), nullable=True, index=True),
        sa.Column('metric_name', sa.String(length=128), nullable=False, index=True),
        sa.Column('metric_value', sa.Float(), nullable=False),
        sa.Column('metrics', sa.JSON(), nullable=True),
        sa.Column('review_rate', sa.Float(), nullable=True),
        sa.Column('average_confidence', sa.Float(), nullable=True),
        sa.Column('processing_time', sa.Float(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id')
    )

    op.create_table(
        'batch_jobs',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('job_name', sa.String(length=256), nullable=False, index=True),
        sa.Column('description', sa.Text(), nullable=True),
        sa.Column('status', sa.Enum('CREATED', 'UPLOADING', 'UPLOADED', 'QUEUED', 'PROCESSING', 'PARTIALLY_COMPLETED', 'COMPLETED', 'FAILED', 'CANCELLED', name='batchstatus'), nullable=True, server_default='CREATED', index=True),
        sa.Column('priority', sa.Integer(), nullable=True, server_default='5', index=True),
        sa.Column('total_documents', sa.Integer(), nullable=True, server_default='0'),
        sa.Column('processed_documents', sa.Integer(), nullable=True, server_default='0'),
        sa.Column('failed_documents', sa.Integer(), nullable=True, server_default='0'),
        sa.Column('completed_documents', sa.Integer(), nullable=True, server_default='0'),
        sa.Column('needs_review_documents', sa.Integer(), nullable=True, server_default='0'),
        sa.Column('celery_group_id', sa.String(length=256), nullable=True, index=True),
        sa.Column('submitted_by', sa.String(length=256), nullable=True),
        sa.Column('client_id', sa.String(length=256), nullable=True, index=True),
        sa.Column('submitted_at', sa.DateTime(), nullable=True),
        sa.Column('processing_started_at', sa.DateTime(), nullable=True),
        sa.Column('processing_completed_at', sa.DateTime(), nullable=True),
        sa.Column('estimated_completion_at', sa.DateTime(), nullable=True),
        sa.Column('zip_file_path', sa.String(length=1024), nullable=True),
        sa.Column('zip_file_size', sa.Integer(), nullable=True),
        sa.Column('extract_dir', sa.String(length=1024), nullable=True),
        sa.Column('job_metadata', sa.JSON(), nullable=True),
        sa.Column('processing_options', sa.JSON(), nullable=True),
        sa.Column('extraction_schema', sa.JSON(), nullable=True),
        sa.Column('progress_percentage', sa.Float(), nullable=True, server_default='0.0'),
        sa.Column('error_message', sa.Text(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id')
    )

    op.create_table(
        'documents',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('batch_id', sa.Integer(), nullable=True, index=True),
        sa.Column('filename', sa.String(length=512), nullable=False, index=True),
        sa.Column('original_filename', sa.String(length=512), nullable=False),
        sa.Column('document_type', sa.Enum('PDF', 'WORD', 'IMAGE', 'TXT', 'UNKNOWN', name='documenttype'), nullable=True, server_default='UNKNOWN', index=True),
        sa.Column('mime_type', sa.String(length=128), nullable=True),
        sa.Column('file_size', sa.Integer(), nullable=True),
        sa.Column('status', sa.Enum('UPLOADED', 'PREPROCESSING', 'PREPROCESSED', 'LAYOUT_ANALYZING', 'LAYOUT_ANALYZED', 'EXTRACTING', 'EXTRACTED', 'VALIDATING', 'VALIDATED', 'NEEDS_REVIEW', 'COMPLETED', 'FAILED', name='documentstatus'), nullable=True, server_default='UPLOADED', index=True),
        sa.Column('priority', sa.Enum('HIGH', 'MEDIUM', 'LOW', name='documentpriority'), nullable=True, server_default='MEDIUM', index=True),
        sa.Column('storage_path', sa.String(length=1024), nullable=False),
        sa.Column('minio_bucket', sa.String(length=256), nullable=True),
        sa.Column('minio_object_name', sa.String(length=1024), nullable=True),
        sa.Column('page_count', sa.Integer(), nullable=True, server_default='0'),
        sa.Column('language', sa.String(length=32), nullable=True),
        sa.Column('error_message', sa.Text(), nullable=True),
        sa.Column('error_stack', sa.Text(), nullable=True),
        sa.Column('processing_started_at', sa.DateTime(), nullable=True),
        sa.Column('processing_completed_at', sa.DateTime(), nullable=True),
        sa.Column('processing_duration', sa.Float(), nullable=True),
        sa.Column('preprocessing_metadata', sa.JSON(), nullable=True),
        sa.Column('ocr_metadata', sa.JSON(), nullable=True),
        sa.Column('layout_metadata', sa.JSON(), nullable=True),
        sa.Column('uploaded_by', sa.String(length=256), nullable=True),
        sa.Column('client_id', sa.String(length=256), nullable=True, index=True),
        sa.Column('claim_number', sa.String(length=256), nullable=True, index=True),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['batch_id'], ['batch_jobs.id'], ),
        sa.PrimaryKeyConstraint('id')
    )

    op.create_table(
        'extraction_results',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('document_id', sa.Integer(), nullable=False, index=True),
        sa.Column('model_version_id', sa.Integer(), nullable=True, index=True),
        sa.Column('schema_id', sa.Integer(), nullable=True, index=True),
        sa.Column('status', sa.Enum('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'NEEDS_REVIEW', name='extractionstatus'), nullable=True, server_default='PENDING', index=True),
        sa.Column('schema_name', sa.String(length=256), nullable=False),
        sa.Column('schema_version', sa.String(length=64), nullable=True),
        sa.Column('overall_confidence', sa.Float(), nullable=True, server_default='0.0'),
        sa.Column('processing_time', sa.Float(), nullable=True),
        sa.Column('model_name', sa.String(length=256), nullable=True),
        sa.Column('model_version', sa.String(length=64), nullable=True),
        sa.Column('raw_extraction', sa.JSON(), nullable=True),
        sa.Column('structured_output', sa.JSON(), nullable=True),
        sa.Column('is_ab_test', sa.Boolean(), nullable=True, server_default='false'),
        sa.Column('ab_test_group', sa.String(length=64), nullable=True),
        sa.Column('error_message', sa.Text(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['document_id'], ['documents.id'], ),
        sa.ForeignKeyConstraint(['model_version_id'], ['model_versions.id'], ),
        sa.ForeignKeyConstraint(['schema_id'], ['extraction_schemas.id'], ),
        sa.PrimaryKeyConstraint('id')
    )

    op.create_table(
        'batch_documents',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('batch_id', sa.Integer(), nullable=False, index=True),
        sa.Column('document_id', sa.Integer(), nullable=False, index=True),
        sa.Column('filename', sa.String(length=512), nullable=True),
        sa.Column('original_path_in_zip', sa.String(length=1024), nullable=True),
        sa.Column('extraction_result_id', sa.Integer(), nullable=True),
        sa.Column('status', sa.Enum('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'NEEDS_REVIEW', name='batchdocumentstatus'), nullable=True, server_default='PENDING', index=True),
        sa.Column('position', sa.Integer(), nullable=True),
        sa.Column('started_at', sa.DateTime(), nullable=True),
        sa.Column('completed_at', sa.DateTime(), nullable=True),
        sa.Column('error_message', sa.Text(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['batch_id'], ['batch_jobs.id'], ),
        sa.ForeignKeyConstraint(['document_id'], ['documents.id'], ),
        sa.ForeignKeyConstraint(['extraction_result_id'], ['extraction_results.id'], ),
        sa.PrimaryKeyConstraint('id')
    )

    op.create_table(
        'extracted_fields',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('extraction_result_id', sa.Integer(), nullable=False, index=True),
        sa.Column('field_name', sa.String(length=256), nullable=False, index=True),
        sa.Column('field_type', sa.Enum('STRING', 'NUMBER', 'DATE', 'BOOLEAN', 'LIST', 'OBJECT', name='fielddatatype'), nullable=True, server_default='STRING'),
        sa.Column('value', sa.Text(), nullable=True),
        sa.Column('normalized_value', sa.Text(), nullable=True),
        sa.Column('confidence', sa.Float(), nullable=True, server_default='0.0', index=True),
        sa.Column('is_low_confidence', sa.Boolean(), nullable=True, server_default='false', index=True),
        sa.Column('page_number', sa.Integer(), nullable=True),
        sa.Column('bounding_box', sa.JSON(), nullable=True),
        sa.Column('text_block', sa.Text(), nullable=True),
        sa.Column('validation_status', sa.Enum('VALID', 'WARNING', 'ERROR', 'UNCHECKED', name='fieldvalidationstatus'), nullable=True, server_default='UNCHECKED', index=True),
        sa.Column('validation_errors', sa.JSON(), nullable=True),
        sa.Column('validation_warnings', sa.JSON(), nullable=True),
        sa.Column('suggested_value', sa.Text(), nullable=True),
        sa.Column('reviewed', sa.Boolean(), nullable=True, server_default='false', index=True),
        sa.Column('reviewed_value', sa.Text(), nullable=True),
        sa.Column('reviewed_by', sa.String(length=256), nullable=True),
        sa.Column('reviewed_at', sa.DateTime(), nullable=True),
        sa.Column('is_used_for_training', sa.Boolean(), nullable=True, server_default='false'),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['extraction_result_id'], ['extraction_results.id'], ),
        sa.PrimaryKeyConstraint('id')
    )

    op.create_table(
        'review_tasks',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('document_id', sa.Integer(), nullable=False, index=True),
        sa.Column('extraction_result_id', sa.Integer(), nullable=True, index=True),
        sa.Column('status', sa.Enum('PENDING', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'ESCALATED', 'CANCELLED', name='reviewstatus'), nullable=True, server_default='PENDING', index=True),
        sa.Column('priority', sa.Enum('HIGH', 'MEDIUM', 'LOW', name='reviewpriority'), nullable=True, server_default='MEDIUM', index=True),
        sa.Column('assigned_to', sa.String(length=256), nullable=True, index=True),
        sa.Column('assigned_at', sa.DateTime(), nullable=True),
        sa.Column('started_at', sa.DateTime(), nullable=True),
        sa.Column('completed_at', sa.DateTime(), nullable=True),
        sa.Column('review_duration', sa.Float(), nullable=True),
        sa.Column('fields_to_review', sa.JSON(), nullable=True),
        sa.Column('review_notes', sa.Text(), nullable=True),
        sa.Column('review_metadata', sa.JSON(), nullable=True),
        sa.Column('completed_by', sa.String(length=256), nullable=True),
        sa.Column('is_correct', sa.Boolean(), nullable=True),
        sa.Column('correction_count', sa.Integer(), nullable=True, server_default='0'),
        sa.Column('has_quality_issues', sa.Boolean(), nullable=True, server_default='false'),
        sa.Column('quality_issue_description', sa.Text(), nullable=True),
        sa.Column('escalated', sa.Boolean(), nullable=True, server_default='false'),
        sa.Column('escalated_to', sa.String(length=256), nullable=True),
        sa.Column('escalated_reason', sa.Text(), nullable=True),
        sa.Column('escalated_at', sa.DateTime(), nullable=True),
        sa.Column('queued_at', sa.DateTime(), nullable=True, index=True),
        sa.Column('deadline_at', sa.DateTime(), nullable=True),
        sa.Column('version', sa.Integer(), nullable=False, server_default='1'),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['document_id'], ['documents.id'], ),
        sa.ForeignKeyConstraint(['extraction_result_id'], ['extraction_results.id'], ),
        sa.PrimaryKeyConstraint('id')
    )

    op.create_table(
        'table_structures',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('document_id', sa.Integer(), nullable=False, index=True),
        sa.Column('page_number', sa.Integer(), nullable=False, index=True),
        sa.Column('table_index', sa.Integer(), nullable=True, server_default='0'),
        sa.Column('table_id', sa.String(length=128), nullable=True, index=True),
        sa.Column('bounding_box', sa.JSON(), nullable=True),
        sa.Column('confidence', sa.Float(), nullable=True, server_default='0.0'),
        sa.Column('row_count', sa.Integer(), nullable=True, server_default='0'),
        sa.Column('col_count', sa.Integer(), nullable=True, server_default='0'),
        sa.Column('has_header', sa.Boolean(), nullable=True, server_default='false'),
        sa.Column('has_merged_cells', sa.Boolean(), nullable=True, server_default='false'),
        sa.Column('is_spanning_pages', sa.Boolean(), nullable=True, server_default='false'),
        sa.Column('raw_detection', sa.JSON(), nullable=True),
        sa.Column('structure_json', sa.JSON(), nullable=True),
        sa.Column('caption', sa.Text(), nullable=True),
        sa.Column('footer', sa.Text(), nullable=True),
        sa.Column('table_type', sa.String(length=128), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['document_id'], ['documents.id'], ),
        sa.PrimaryKeyConstraint('id')
    )

    op.create_table(
        'review_comments',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('review_task_id', sa.Integer(), nullable=False, index=True),
        sa.Column('comment_text', sa.Text(), nullable=False),
        sa.Column('comment_type', sa.String(length=64), nullable=True),
        sa.Column('field_name', sa.String(length=256), nullable=True),
        sa.Column('old_value', sa.Text(), nullable=True),
        sa.Column('new_value', sa.Text(), nullable=True),
        sa.Column('commenter', sa.String(length=256), nullable=False),
        sa.Column('is_resolved', sa.Boolean(), nullable=True, server_default='false'),
        sa.Column('resolved_by', sa.String(length=256), nullable=True),
        sa.Column('resolved_at', sa.DateTime(), nullable=True),
        sa.Column('bounding_box', sa.JSON(), nullable=True),
        sa.Column('page_number', sa.Integer(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['review_task_id'], ['review_tasks.id'], ),
        sa.PrimaryKeyConstraint('id')
    )

    op.create_table(
        'table_cells',
        sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
        sa.Column('table_id', sa.Integer(), nullable=False, index=True),
        sa.Column('row_index', sa.Integer(), nullable=False),
        sa.Column('col_index', sa.Integer(), nullable=False),
        sa.Column('row_span', sa.Integer(), nullable=True, server_default='1'),
        sa.Column('col_span', sa.Integer(), nullable=True, server_default='1'),
        sa.Column('is_header', sa.Boolean(), nullable=True, server_default='false'),
        sa.Column('is_merged', sa.Boolean(), nullable=True, server_default='false'),
        sa.Column('text', sa.Text(), nullable=True),
        sa.Column('normalized_text', sa.Text(), nullable=True),
        sa.Column('confidence', sa.Float(), nullable=True, server_default='0.0'),
        sa.Column('bounding_box', sa.JSON(), nullable=True),
        sa.Column('cell_html', sa.Text(), nullable=True),
        sa.Column('data_type', sa.String(length=64), nullable=True),
        sa.Column('value', sa.JSON(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False, index=True),
        sa.Column('updated_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['table_id'], ['table_structures.id'], ),
        sa.PrimaryKeyConstraint('id')
    )


def downgrade() -> None:
    op.drop_table('table_cells')
    op.drop_table('review_comments')
    op.drop_table('table_structures')
    op.drop_table('review_tasks')
    op.drop_table('extracted_fields')
    op.drop_table('batch_documents')
    op.drop_table('extraction_results')
    op.drop_table('documents')
    op.drop_table('batch_jobs')
    op.drop_table('ab_test_results')
    op.drop_table('ab_test_experiments')
    op.drop_table('model_versions')
    op.drop_table('extraction_schemas')

    op.execute('DROP TYPE IF EXISTS fielddatatype')
    op.execute('DROP TYPE IF EXISTS extractionstatus')
    op.execute('DROP TYPE IF EXISTS fieldvalidationstatus')
    op.execute('DROP TYPE IF EXISTS documenttype')
    op.execute('DROP TYPE IF EXISTS documentstatus')
    op.execute('DROP TYPE IF EXISTS documentpriority')
    op.execute('DROP TYPE IF EXISTS modeltype')
    op.execute('DROP TYPE IF EXISTS modelstatus')
    op.execute('DROP TYPE IF EXISTS batchstatus')
    op.execute('DROP TYPE IF EXISTS batchdocumentstatus')
    op.execute('DROP TYPE IF EXISTS reviewstatus')
    op.execute('DROP TYPE IF EXISTS reviewpriority')
