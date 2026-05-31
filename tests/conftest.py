import asyncio
import pytest
import sys
from pathlib import Path

root_path = Path(__file__).parent.parent
sys.path.insert(0, str(root_path))


@pytest.fixture(scope="session")
def event_loop():
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()


@pytest.fixture
def mock_settings(monkeypatch):
    from wallethub.config import get_settings

    original = get_settings()

    class MockSettings:
        secret_key = "test-secret-key-for-testing-only"
        environment = "test"
        debug = True
        log_level = "debug"

        class Database:
            url = "sqlite+aiosqlite:///:memory:"
            echo = False
            pool_size = 5
            max_overflow = 10

        class Redis:
            host = "localhost"
            port = 6379
            db = 0

        class IPFS:
            gateway_url = "https://ipfs.io/ipfs/"
            api_url = "http://localhost:5001"
            pinata_api_key = "test-pinata-key"
            pinata_secret_api_key = "test-pinata-secret"

        class Arweave:
            gateway_url = "https://arweave.net/"
            wallet_path = None

        database = Database()
        redis = Redis()
        ipfs = IPFS()
        arweave = Arweave()

        chains = original.chains
        default_chain = "ethereum"
        gas_estimate_blocks = 10
        gas_estimate_percentile = 50
        event_listener_poll_interval = 0.5
        event_listener_max_blocks_per_poll = 10
        max_concurrent_tasks = 10
        task_timeout_seconds = 30

    mock = MockSettings()
    monkeypatch.setattr("wallethub.config.get_settings", lambda: mock)
    return mock


@pytest.fixture
def sample_addresses():
    return {
        "alice": "0x742d35Cc6634C0532925a3b844Bc9973A9bffdb3",
        "bob": "0x8626f6940E2eb28930eFb4CeF49B2d1F2C9C1199",
        "charlie": "0xAb5801a7D398351b8bE11C439e05C5B3259aeC9B",
        "dave": "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
        "multisig": "0x1234567890123456789012345678901234567890",
    }


@pytest.fixture
def sample_private_keys():
    return {
        "alice": "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
        "bob": "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d",
        "charlie": "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a",
    }
