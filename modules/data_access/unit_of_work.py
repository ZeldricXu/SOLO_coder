from typing import Any, Dict, Optional, Type, TypeVar
from sqlalchemy.ext.asyncio import AsyncSession

from core import get_db_context

T = TypeVar("T")


class UnitOfWork:
    def __init__(self, db: Optional[AsyncSession] = None):
        self._db = db
        self._repositories: Dict[str, Any] = {}
        self._transaction_started = False

    async def __aenter__(self):
        if self._db is None:
            self._session_context = get_db_context()
            self._db = await self._session_context.__aenter__()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        if hasattr(self, '_session_context'):
            await self._session_context.__aexit__(exc_type, exc_val, exc_tb)

    def get_repository(self, repo_class: Type[T]) -> T:
        repo_name = repo_class.__name__
        if repo_name not in self._repositories:
            self._repositories[repo_name] = repo_class(self._db)
        return self._repositories[repo_name]

    async def commit(self) -> None:
        await self._db.commit()

    async def rollback(self) -> None:
        await self._db.rollback()

    async def flush(self) -> None:
        await self._db.flush()

    async def refresh(self, instance: Any) -> None:
        await self._db.refresh(instance)


class UnitOfWorkFactory:
    @staticmethod
    async def create() -> UnitOfWork:
        return UnitOfWork()

    @staticmethod
    async def with_db(db: AsyncSession) -> UnitOfWork:
        return UnitOfWork(db)
