import pytest
import asyncio
from typing import Generator, AsyncGenerator
from faker import Faker
import logging
import os
from dotenv import load_dotenv

load_dotenv('.env.test')

logging.basicConfig(
    level=os.getenv('LOG_LEVEL', 'INFO'),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

fake = Faker('zh_CN')


@pytest.fixture(scope="session")
def event_loop() -> Generator:
    """创建全局事件循环"""
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()


@pytest.fixture(scope="session")
def base_url() -> str:
    """获取基础URL"""
    return os.getenv('BASE_URL', 'http://localhost:8080')


@pytest.fixture(scope="session")
def api_prefix() -> str:
    """获取API前缀"""
    return os.getenv('API_PREFIX', '/api/v1')


@pytest.fixture(scope="session")
def default_timeout() -> int:
    """获取默认超时时间"""
    return int(os.getenv('DEFAULT_TIMEOUT', 30))


@pytest.fixture
def faker() -> Faker:
    """获取Faker实例"""
    return fake
