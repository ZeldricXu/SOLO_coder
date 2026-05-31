from __future__ import annotations

import asyncio
from typing import Any, AsyncGenerator, Dict, Optional

import pytest
import pytest_asyncio

from src.shared.config import settings
from src.shared.types import Chain


@pytest.fixture(scope="session")
def event_loop():
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()


@pytest_asyncio.fixture
async def mock_chain_config():
    original_chains = settings.chains.copy()
    settings.chains = {
        "ethereum": {
            "rpc_url": "https://eth-mainnet.g.alchemy.com/v2/demo",
            "chain_id": 1,
            "name": "Ethereum Mainnet",
        }
    }
    yield
    settings.chains = original_chains


@pytest.fixture
def sample_address() -> str:
    return "0x742d35Cc6634C0532925a3b844Bc9e7595f5bD1C"


@pytest.fixture
def sample_contract_address() -> str:
    return "0xdAC17F958D2ee523a2206206994597C13D831ec7"


@pytest.fixture
def sample_tx_hash() -> str:
    return "0x" + "a" * 64


@pytest.fixture
def sample_private_key() -> str:
    return "0x" + "b" * 64


@pytest.fixture
def sample_abi() -> list[Dict[str, Any]]:
    return [
        {
            "anonymous": False,
            "inputs": [
                {"indexed": True, "name": "from", "type": "address"},
                {"indexed": True, "name": "to", "type": "address"},
                {"indexed": False, "name": "value", "type": "uint256"},
            ],
            "name": "Transfer",
            "type": "event",
        },
        {
            "inputs": [
                {"name": "to", "type": "address"},
                {"name": "value", "type": "uint256"},
            ],
            "name": "transfer",
            "outputs": [{"name": "", "type": "bool"}],
            "stateMutability": "nonpayable",
            "type": "function",
        },
    ]


@pytest.fixture
def sample_event_log(sample_tx_hash: str, sample_contract_address: str) -> Dict[str, Any]:
    return {
        "log_index": 1,
        "transaction_hash": sample_tx_hash,
        "transaction_index": 10,
        "block_hash": "0x" + "c" * 64,
        "block_number": 12345678,
        "address": sample_contract_address,
        "data": "0x" + "d" * 64,
        "topics": ["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"],
        "removed": False,
    }
