from typing import AsyncGenerator
import pytest
from httpx import AsyncClient, ASGITransport
from src.main import app
from src.di import get_container, DIContainer


@pytest.fixture(scope="session")
def anyio_backend():
    return "asyncio"


@pytest.fixture
async def client() -> AsyncGenerator[AsyncClient, None]:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.fixture
def container() -> DIContainer:
    return get_container()


@pytest.fixture
def trace_id() -> str:
    from src.core import generate_id
    return generate_id("trace")
