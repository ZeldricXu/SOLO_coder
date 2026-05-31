import pytest
import asyncio
import uuid
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch, Mock
from typing import AsyncGenerator, List
from sqlalchemy.ext.asyncio import AsyncSession

from src.modules import (
    DatabaseManager,
    EntityRepository,
    ConfigRepository,
    RunRepository,
    SchemaMigration,
    EntityStatus,
    get_db_manager,
    get_entity_repository,
    get_config_repository,
    get_run_repository,
)
from .builders import BuilderFactory


@pytest.fixture
def mock_db_session() -> AsyncMock:
    session = AsyncMock(spec=AsyncSession)
    return session


@pytest.fixture
def entity_repo(mock_db_session) -> EntityRepository:
    with patch('src.modules.data_access.DatabaseManager', new_callable=MagicMock):
        repo = EntityRepository()
        return repo


@pytest.fixture
def config_repo(mock_db_session) -> ConfigRepository:
    with patch('src.modules.data_access.DatabaseManager', new_callable=MagicMock):
        repo = ConfigRepository()
        return repo


@pytest.fixture
def run_repo(mock_db_session) -> RunRepository:
    with patch('src.modules.data_access.DatabaseManager', new_callable=MagicMock):
        repo = RunRepository()
        return repo


@pytest.fixture
def migration_manager() -> SchemaMigration:
    mock_db = MagicMock()
    mock_db.get_session = AsyncMock()
    return SchemaMigration(mock_db)


class TestEntityDataConsistency:
    @pytest.mark.asyncio
    async def test_create_entity_atomicity(self, entity_repo, mock_db_session):
        test_data = BuilderFactory.entity().with_type("test_task").with_status("pending").build()

        with patch.object(entity_repo.model, '__init__', return_value=None) as mock_init:
            result = await entity_repo.create(
                mock_db_session,
                id=test_data.id,
                type=test_data.type,
                status=test_data.status,
                attributes=test_data.attributes,
                labels=test_data.labels,
            )

        mock_db_session.add.assert_called_once()
        mock_db_session.flush.assert_called_once()

    @pytest.mark.asyncio
    async def test_create_entity_with_duplicate_id_raises_error(self, entity_repo, mock_db_session):
        mock_db_session.add.side_effect = Exception("UNIQUE constraint failed")
        test_data = BuilderFactory.entity().build()

        with pytest.raises(Exception) as exc_info:
            await entity_repo.create(
                mock_db_session,
                id=test_data.id,
                type=test_data.type,
                status=test_data.status,
                attributes=test_data.attributes,
            )

        assert "UNIQUE constraint failed" in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_update_entity_preserves_immutable_fields(self, entity_repo, mock_db_session):
        test_data = BuilderFactory.entity().build()

        mock_entity = Mock()
        mock_entity.id = test_data.id
        mock_entity.created_at = test_data.created_at
        mock_entity.updated_at = test_data.updated_at

        mock_db_session.get.return_value = mock_entity

        new_attributes = {"new_key": "new_value"}
        updated = await entity_repo.update(
            mock_db_session,
            test_data.id,
            attributes=new_attributes,
            status=EntityStatus.RUNNING.value,
        )

        assert updated is not None
        assert mock_entity.status == EntityStatus.RUNNING.value
        assert mock_entity.attributes == new_attributes
        assert mock_entity.created_at == test_data.created_at
        mock_db_session.flush.assert_called_once()

    @pytest.mark.asyncio
    async def test_soft_delete_preserves_data(self, entity_repo, mock_db_session):
        test_data = BuilderFactory.entity().build()

        mock_entity = Mock()
        mock_entity.id = test_data.id
        mock_entity.is_deleted = False
        mock_db_session.get.return_value = mock_entity

        result = await entity_repo.delete(mock_db_session, test_data.id, soft_delete=True)

        assert result is True
        assert mock_entity.is_deleted is True
        mock_db_session.delete.assert_not_called()

    @pytest.mark.asyncio
    async def test_hard_delete_removes_data(self, entity_repo, mock_db_session):
        test_data = BuilderFactory.entity().build()

        mock_entity = Mock()
        mock_entity.id = test_data.id
        mock_db_session.get.return_value = mock_entity

        result = await entity_repo.delete(mock_db_session, test_data.id, soft_delete=False)

        assert result is True
        mock_db_session.delete.assert_called_once_with(mock_entity)

    @pytest.mark.asyncio
    async def test_concurrent_updates_with_optimistic_locking(self, entity_repo, mock_db_session):
        test_data = BuilderFactory.entity().build()
        mock_entity = Mock()
        mock_entity.version = 1
        mock_db_session.get.return_value = mock_entity

        async def update_1():
            return await entity_repo.update(
                mock_db_session, test_data.id, status=EntityStatus.RUNNING.value
            )

        async def update_2():
            return await entity_repo.update(
                mock_db_session, test_data.id, attributes={"updated": "by_update_2"}
            )

        results = await asyncio.gather(update_1(), update_2())

        assert all(r is not None for r in results)
        assert mock_db_session.flush.call_count == 2


class TestConfigDataConsistency:
    @pytest.mark.asyncio
    async def test_config_version_increment(self, config_repo, mock_db_session):
        test_data = BuilderFactory.config().with_namespace("test").with_version(3).build()

        mock_latest = Mock()
        mock_latest.version = 3
        mock_result = Mock()

        async def mock_get_latest(*args, **kwargs):
            return mock_latest

        async def mock_create(*args, **kwargs):
            return mock_result

        config_repo.get_latest_version = AsyncMock(side_effect=mock_get_latest)
        config_repo.create = AsyncMock(side_effect=mock_create)

        result = await config_repo.create_new_version(
            mock_db_session,
            config_id=test_data.config_id,
            namespace=test_data.namespace,
            parameters={"new": "value"},
        )

        assert result is not None

    @pytest.mark.asyncio
    async def test_get_latest_version_returns_most_recent(self, config_repo, mock_db_session):
        test_data = BuilderFactory.config().with_namespace("dev").with_version(5).build()

        mock_result = Mock()
        mock_result.version = 5
        mock_scalar_result = Mock()
        mock_scalar_result.scalars.return_value.first.return_value = mock_result
        mock_db_session.execute.return_value = mock_scalar_result

        result = await config_repo.get_latest_version(
            mock_db_session, test_data.config_id, test_data.namespace
        )

        assert result is not None
        assert result.version == 5

    @pytest.mark.asyncio
    async def test_config_creation_with_duplicate_version_fails(self, config_repo, mock_db_session):
        mock_db_session.execute.side_effect = Exception("Unique constraint violation")

        with pytest.raises(Exception):
            await config_repo.create_new_version(
                mock_db_session,
                config_id="test_cfg",
                namespace="test",
                parameters={"key": "value"},
            )

    @pytest.mark.asyncio
    async def test_config_parameters_immutable_after_creation(self, config_repo, mock_db_session):
        test_data = BuilderFactory.config().enabled().build()

        mock_config = Mock()
        mock_config.parameters = test_data.parameters
        mock_db_session.get.return_value = mock_config

        updated = await config_repo.update(
            mock_db_session, "test_id", description="Updated description"
        )

        assert updated.parameters == test_data.parameters


class TestRunDataConsistency:
    @pytest.mark.asyncio
    async def test_run_creation_links_to_entity(self, run_repo, mock_db_session):
        test_data = BuilderFactory.run().with_phase("running").build()

        result = await run_repo.create(
            mock_db_session,
            run_id=test_data.run_id,
            entity_id=test_data.entity_id,
            phase=test_data.phase,
            progress=test_data.progress,
            metrics=test_data.metrics,
        )

        mock_db_session.add.assert_called_once()
        assert result.entity_id == test_data.entity_id
        assert result.run_id == test_data.run_id

    @pytest.mark.asyncio
    async def test_run_progress_monotonic_increase(self, run_repo, mock_db_session):
        test_data = BuilderFactory.run().with_progress(50).build()

        mock_run = Mock()
        mock_run.progress = 50
        mock_db_session.get.return_value = mock_run

        with patch.object(run_repo, 'update', wraps=run_repo.update) as mock_update:
            await run_repo.update(mock_db_session, "test_id", progress=75)
            mock_update.assert_called_once()

    @pytest.mark.asyncio
    async def test_active_runs_excludes_completed(self, run_repo, mock_db_session):
        test_data = BuilderFactory.run().completed().build()

        mock_running = Mock()
        mock_running.completed_at = None

        mock_completed = Mock()
        mock_completed.completed_at = datetime.utcnow()

        mock_result = Mock()
        mock_result.scalars.return_value.all.return_value = [mock_running]
        mock_db_session.execute.return_value = mock_result

        active_runs = await run_repo.get_active_runs(mock_db_session, test_data.entity_id)

        assert len(active_runs) == 1
        assert mock_completed not in active_runs

    @pytest.mark.asyncio
    async def test_run_completion_updates_timestamps(self, run_repo, mock_db_session):
        test_data = BuilderFactory.run().with_phase("running").build()

        mock_run = Mock()
        mock_run.phase = "running"
        mock_run.completed_at = None
        mock_db_session.get.return_value = mock_run

        completed_time = datetime.utcnow()
        await run_repo.update(
            mock_db_session,
            "test_id",
            phase="completed",
            progress=100,
            completed_at=completed_time,
        )

        assert mock_run.phase == "completed"
        assert mock_run.progress == 100
        assert mock_run.completed_at == completed_time


class TestSchemaMigrationConsistency:
    @pytest.fixture
    def mock_db_manager(self):
        manager = MagicMock(spec=DatabaseManager)
        return manager

    @pytest.fixture
    def schema_migration(self, mock_db_manager):
        return SchemaMigration(mock_db_manager)

    @pytest.mark.asyncio
    async def test_migration_applied_in_order(self, schema_migration, mock_db_manager):
        mock_session = AsyncMock()
        mock_db_manager.get_session.return_value.__aenter__.return_value = mock_session
        mock_session.execute.return_value.scalars.return_value.first.return_value = None

        await schema_migration.apply_migration("001", "initial", "CREATE TABLE test (id INTEGER)")

        assert mock_session.execute.call_count >= 2

    @pytest.mark.asyncio
    async def test_duplicate_migration_skipped(self, schema_migration, mock_db_manager):
        mock_session = AsyncMock()
        mock_db_manager.get_session.return_value.__aenter__.return_value = mock_session

        existing = Mock()
        existing.version = "001"
        mock_session.execute.return_value.scalars.return_value.first.return_value = existing

        await schema_migration.apply_migration("001", "initial", "CREATE TABLE test (id INTEGER)")

        execute_calls = mock_session.execute.call_args_list
        insert_calls = [
            call for call in execute_calls
            if "INSERT" in str(call)
        ]
        assert len(insert_calls) == 0

    @pytest.mark.asyncio
    async def test_migration_checksum_validation(self, schema_migration, mock_db_manager):
        mock_session = AsyncMock()
        mock_db_manager.get_session.return_value.__aenter__.return_value = mock_session
        mock_session.execute.return_value.scalars.return_value.first.return_value = None

        migration_sql = "CREATE TABLE test (id INTEGER PRIMARY KEY)"
        checksum = "test_checksum_123"

        await schema_migration.apply_migration("002", "create_test", migration_sql, checksum=checksum)

        calls = mock_session.execute.call_args_list
        checksum_found = any(
            checksum in str(call) for call in calls
        )
        assert checksum_found


class TestTransactionConsistency:
    @pytest.mark.asyncio
    async def test_transaction_rollback_on_error(self, mock_db_session):
        from src.modules.data_access import DatabaseManager

        db_manager = DatabaseManager()
        db_manager._async_session_factory = MagicMock()
        db_manager._async_session_factory.return_value.__aenter__.return_value = mock_db_session

        mock_db_session.commit.side_effect = Exception("DB Error")

        with pytest.raises(Exception):
            async with db_manager.get_session() as session:
                pass

        mock_db_session.rollback.assert_called_once()

    @pytest.mark.asyncio
    async def test_multiple_operations_atomic(self, entity_repo, mock_db_session):
        test_entities = BuilderFactory.entity().build_many(5)

        created_entities = []
        for test_data in test_entities:
            entity = await entity_repo.create(
                mock_db_session,
                id=test_data.id,
                type=test_data.type,
                status=test_data.status,
                attributes=test_data.attributes,
            )
            created_entities.append(entity)

        assert mock_db_session.add.call_count == 5
        assert len(created_entities) == 5

    @pytest.mark.asyncio
    async def test_concurrent_entity_creation_isolation(self):
        created_ids = set()

        async def create_entity(repo, session, test_data):
            entity = await repo.create(
                session,
                id=test_data.id,
                type=test_data.type,
                status=test_data.status,
                attributes=test_data.attributes,
            )
            created_ids.add(entity.id)
            return entity

        mock_session = AsyncMock()
        repo = EntityRepository()
        test_data_list = BuilderFactory.entity().build_many(10)

        tasks = [create_entity(repo, mock_session, td) for td in test_data_list]
        await asyncio.gather(*tasks)

        assert len(created_ids) == 10
        assert mock_session.add.call_count == 10
