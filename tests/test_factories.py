"""
测试数据构造模块
==================
独立的测试数据工厂，避免在各测试函数中硬编码测试数据。
"""

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from datetime import datetime, timezone
from eth_account import Account
import secrets


class TestAddresses:
    """预定义的测试地址集合"""

    ALICE = "0x742d35Cc6634C0532925a3b844Bc9973A9bffdb3"
    BOB = "0x8626f6940E2eb28930eFb4CeF49B2d1F2C9C1199"
    CHARLIE = "0xAb5801a7D398351b8bE11C439e05C5B3259aeC9B"
    DAVE = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"
    MULTISIG_WALLET = "0x1234567890123456789012345678901234567890"
    ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
    TOKEN_CONTRACT = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
    BRIDGE_CONTRACT = "0x5f4eC3Df9cbd43714FE2740f5E3616155c5b8419"


class TestPrivateKeys:
    """预定义的测试私钥（仅用于测试，切勿在生产环境使用）"""

    ALICE = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
    BOB = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    CHARLIE = "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a"


class TestChainIds:
    """测试用链ID"""

    ETHEREUM = 1
    SEPOLIA = 11155111
    POLYGON = 137
    BSC = 56
    ARBITRUM = 42161
    OPTIMISM = 10


@dataclass
class TransactionTestData:
    """交易测试数据结构"""

    from_address: str
    to_address: str
    value: int
    data: Optional[str]
    nonce: int
    gas_limit: int
    gas_price: Optional[int]
    max_fee_per_gas: Optional[int]
    max_priority_fee_per_gas: Optional[int]
    chain: str


class TransactionFactory:
    """交易测试数据工厂"""

    @staticmethod
    def create_simple_transfer() -> Dict[str, Any]:
        """创建简单的ETH转账测试数据"""
        return {
            "chain": "ethereum",
            "from_address": TestAddresses.ALICE,
            "to_address": TestAddresses.BOB,
            "value": 1000000000000000000,
            "gas_limit": 21000,
            "gas_price": 20000000000,
        }

    @staticmethod
    def create_eip1559_transfer() -> Dict[str, Any]:
        """创建EIP-1559转账测试数据"""
        return {
            "chain": "ethereum",
            "from_address": TestAddresses.ALICE,
            "to_address": TestAddresses.BOB,
            "value": 500000000000000000,
            "gas_limit": 21000,
            "max_fee_per_gas": 30000000000,
            "max_priority_fee_per_gas": 2000000000,
        }

    @staticmethod
    def create_contract_deployment(bytecode_len: int = 1000) -> Dict[str, Any]:
        """创建合约部署测试数据"""
        bytecode = "0x" + secrets.token_hex(bytecode_len)
        return {
            "chain": "ethereum",
            "from_address": TestAddresses.ALICE,
            "to_address": None,
            "value": 0,
            "data": bytecode,
            "gas_limit": 2000000,
            "max_fee_per_gas": 50000000000,
            "max_priority_fee_per_gas": 3000000000,
        }

    @staticmethod
    def create_erc20_transfer(token_address: str, amount: int = 100) -> Dict[str, Any]:
        """创建ERC20转账测试数据"""
        padded_amount = hex(amount)[2:].zfill(64)
        padded_to = TestAddresses.BOB[2:].zfill(64)
        method_id = "a9059cbb"
        data = "0x" + method_id + padded_to + padded_amount

        return {
            "chain": "ethereum",
            "from_address": TestAddresses.ALICE,
            "to_address": token_address,
            "value": 0,
            "data": data,
            "gas_limit": 100000,
            "gas_price": 25000000000,
        }

    @staticmethod
    def create_invalid_transactions() -> List[Dict[str, Any]]:
        """创建各种异常交易测试数据"""
        return [
            {
                "name": "negative_value",
                "data": {
                    "chain": "ethereum",
                    "from_address": TestAddresses.ALICE,
                    "to_address": TestAddresses.BOB,
                    "value": -1,
                    "gas_limit": 21000,
                    "gas_price": 20000000000,
                },
                "expected_error": "Value cannot be negative",
            },
            {
                "name": "gas_limit_too_low",
                "data": {
                    "chain": "ethereum",
                    "from_address": TestAddresses.ALICE,
                    "to_address": TestAddresses.BOB,
                    "value": 0,
                    "gas_limit": 20000,
                    "gas_price": 20000000000,
                },
                "expected_error": "Gas limit cannot be less than 21000",
            },
            {
                "name": "missing_to_and_data",
                "data": {
                    "chain": "ethereum",
                    "from_address": TestAddresses.ALICE,
                    "to_address": None,
                    "value": 0,
                    "data": None,
                    "gas_limit": 21000,
                    "gas_price": 20000000000,
                },
                "expected_error": "Either to_address or data must be provided",
            },
            {
                "name": "negative_gas_price",
                "data": {
                    "chain": "ethereum",
                    "from_address": TestAddresses.ALICE,
                    "to_address": TestAddresses.BOB,
                    "value": 0,
                    "gas_limit": 21000,
                    "gas_price": -1,
                },
                "expected_error": "Gas price cannot be negative",
            },
            {
                "name": "max_fee_less_than_priority",
                "data": {
                    "chain": "ethereum",
                    "from_address": TestAddresses.ALICE,
                    "to_address": TestAddresses.BOB,
                    "value": 0,
                    "gas_limit": 21000,
                    "max_fee_per_gas": 1000000000,
                    "max_priority_fee_per_gas": 2000000000,
                },
                "expected_error": "max_fee_per_gas cannot be less than max_priority_fee_per_gas",
            },
        ]

    @staticmethod
    def create_multi_sig_wallet_config() -> Dict[str, Any]:
        """创建多签钱包配置测试数据"""
        return {
            "name": "Test MultiSig Wallet",
            "chain": "ethereum",
            "owners": [TestAddresses.ALICE, TestAddresses.BOB, TestAddresses.CHARLIE],
            "threshold": 2,
            "safe_address": TestAddresses.MULTISIG_WALLET,
        }

    @staticmethod
    def create_multi_sig_proposal() -> Dict[str, Any]:
        """创建多签提案测试数据"""
        return {
            "to_address": TestAddresses.DAVE,
            "value": 1000000000000000000,
            "data": None,
        }


class CrossChainFactory:
    """跨链测试数据工厂"""

    @staticmethod
    def create_bridge_transfer() -> Dict[str, Any]:
        """创建桥接转账测试数据"""
        return {
            "source_chain": "ethereum",
            "target_chain": "polygon",
            "source_address": TestAddresses.ALICE,
            "target_address": TestAddresses.BOB,
            "token_address": TestAddresses.TOKEN_CONTRACT,
            "amount": 100000000000000000000,
        }

    @staticmethod
    def create_atomic_swap() -> Dict[str, Any]:
        """创建原子交换测试数据"""
        return {
            "source_chain": "ethereum",
            "target_chain": "bsc",
            "initiator": TestAddresses.ALICE,
            "participant": TestAddresses.BOB,
            "source_token": TestAddresses.TOKEN_CONTRACT,
            "target_token": "0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c",
            "source_amount": 10000000000000000000,
            "target_amount": 5000000000000000000,
            "timelock": 3600,
        }

    @staticmethod
    def create_cross_chain_message() -> Dict[str, Any]:
        """创建跨链消息测试数据"""
        return {
            "source_chain": "ethereum",
            "target_chain": "polygon",
            "source_tx_hash": "0x" + secrets.token_hex(32),
            "target_address": TestAddresses.BOB,
            "payload": {
                "action": "mint",
                "amount": "100000000000000000000",
                "recipient": TestAddresses.BOB,
            },
            "block_number": 18000000,
        }

    @staticmethod
    def create_concurrent_transfers(count: int = 10) -> List[Dict[str, Any]]:
        """创建批量并发转账测试数据"""
        transfers = []
        for i in range(count):
            transfer = CrossChainFactory.create_bridge_transfer()
            transfer["amount"] = (i + 1) * 1000000000000000000
            transfer["target_address"] = f"0x{secrets.token_hex(20)}"
            transfers.append(transfer)
        return transfers


class StorageFactory:
    """存储测试数据工厂"""

    @staticmethod
    def create_test_content(size_kb: int = 1) -> bytes:
        """创建测试内容数据"""
        return secrets.token_bytes(size_kb * 1024)

    @staticmethod
    def create_test_json() -> Dict[str, Any]:
        """创建测试JSON数据"""
        return {
            "name": "Test Document",
            "version": "1.0.0",
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "data": {
                "field1": "value1",
                "field2": 42,
                "field3": True,
                "nested": {"key": "value"},
            },
            "tags": ["test", "ipfs", "storage"],
        }

    @staticmethod
    def create_large_content(chunks: int = 10) -> bytes:
        """创建大文件测试数据"""
        content = b""
        for i in range(chunks):
            content += f"Chunk {i}: {secrets.token_hex(512)}\n".encode()
        return content

    @staticmethod
    def create_metadata() -> Dict[str, Any]:
        """创建元数据测试数据"""
        return {
            "filename": "test_document.json",
            "content_type": "application/json",
            "author": "Test User",
            "description": "Test content for storage validation",
            "tags": ["test", "automated"],
        }

    @staticmethod
    def create_pin_requests(count: int = 5) -> List[Dict[str, Any]]:
        """创建批量Pin测试数据"""
        return [
            {
                "cid": f"Qm{secrets.token_hex(44)}",
                "network": "ipfs",
                "service": "local",
            }
            for _ in range(count)
        ]


class EventFactory:
    """事件测试数据工厂"""

    @staticmethod
    def create_erc20_transfer_event_abi() -> Dict[str, Any]:
        """创建ERC20 Transfer事件ABI"""
        return {
            "anonymous": False,
            "inputs": [
                {"indexed": True, "name": "from", "type": "address"},
                {"indexed": True, "name": "to", "type": "address"},
                {"indexed": False, "name": "value", "type": "uint256"},
            ],
            "name": "Transfer",
            "type": "event",
        }

    @staticmethod
    def create_approval_event_abi() -> Dict[str, Any]:
        """创建ERC20 Approval事件ABI"""
        return {
            "anonymous": False,
            "inputs": [
                {"indexed": True, "name": "owner", "type": "address"},
                {"indexed": True, "name": "spender", "type": "address"},
                {"indexed": False, "name": "value", "type": "uint256"},
            ],
            "name": "Approval",
            "type": "event",
        }

    @staticmethod
    def create_event_log_data() -> Dict[str, Any]:
        """创建事件日志测试数据"""
        return {
            "address": TestAddresses.TOKEN_CONTRACT,
            "blockHash": "0x" + secrets.token_hex(32),
            "blockNumber": hex(18000000),
            "data": "0x" + secrets.token_hex(32),
            "logIndex": "0x0",
            "removed": False,
            "topics": [
                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                "0x" + TestAddresses.ALICE[2:].zfill(64),
                "0x" + TestAddresses.BOB[2:].zfill(64),
            ],
            "transactionHash": "0x" + secrets.token_hex(32),
            "transactionIndex": "0x1",
        }


class WalletFactory:
    """钱包测试数据工厂"""

    @staticmethod
    def create_mnemonic() -> str:
        """创建测试助记词"""
        return "test test test test test test test test test test test junk"

    @staticmethod
    def create_wallet_config() -> Dict[str, Any]:
        """创建钱包配置测试数据"""
        return {
            "name": "Test Wallet",
            "passphrase": "test-passphrase",
            "store_mnemonic": True,
        }

    @staticmethod
    def create_address_book_entry() -> Dict[str, Any]:
        """创建地址簿条目测试数据"""
        return {
            "address": TestAddresses.BOB,
            "chain": "ethereum",
            "label": "Bob's Wallet",
            "tags": ["personal", "friends"],
            "is_own": False,
        }


class TestDataGenerator:
    """通用测试数据生成器"""

    @staticmethod
    def random_address() -> str:
        """生成随机地址"""
        return "0x" + secrets.token_hex(20)

    @staticmethod
    def random_private_key() -> str:
        """生成随机私钥"""
        account = Account.create()
        return account.key.hex()

    @staticmethod
    def random_tx_hash() -> str:
        """生成随机交易哈希"""
        return "0x" + secrets.token_hex(32)

    @staticmethod
    def random_cid() -> str:
        """生成随机CID"""
        return "Qm" + secrets.token_hex(44)

    @staticmethod
    def random_amount(min: int = 1, max: int = 1000000) -> int:
        """生成随机金额"""
        return secrets.randbelow(max - min + 1) + min

    @staticmethod
    def create_block_data(block_number: int = 18000000) -> Dict[str, Any]:
        """创建区块测试数据"""
        return {
            "number": block_number,
            "hash": "0x" + secrets.token_hex(32),
            "parentHash": "0x" + secrets.token_hex(32),
            "timestamp": int(datetime.now(timezone.utc).timestamp()),
            "difficulty": 1234567890,
            "totalDifficulty": 9876543210,
            "gasLimit": 30000000,
            "gasUsed": 15000000,
            "baseFeePerGas": 20000000000,
            "miner": TestAddresses.CHARLIE,
            "transactions": [TestDataGenerator.random_tx_hash() for _ in range(50)],
        }


@dataclass
class Scenario:
    """测试场景封装"""

    name: str
    description: str
    data: Dict[str, Any]
    expected_result: Any
    setup_steps: List[callable] = field(default_factory=list)
    cleanup_steps: List[callable] = field(default_factory=list)


class ScenarioLibrary:
    """测试场景库"""

    @staticmethod
    def multisig_full_flow() -> Scenario:
        """多签完整流程测试场景"""
        return Scenario(
            name="multisig_full_flow",
            description="完整测试多签提案创建、签名、执行流程",
            data={
                "wallet_config": TransactionFactory.create_multi_sig_wallet_config(),
                "proposal": TransactionFactory.create_multi_sig_proposal(),
                "signers": [TestPrivateKeys.ALICE, TestPrivateKeys.BOB],
            },
            expected_result={"status": "fully_signed", "signatures_count": 2},
        )

    @staticmethod
    def cross_chain_atomic_swap() -> Scenario:
        """跨链原子交换测试场景"""
        return Scenario(
            name="cross_chain_atomic_swap",
            description="测试HTLC原子交换的完整生命周期",
            data={
                "swap": CrossChainFactory.create_atomic_swap(),
                "initiator_key": TestPrivateKeys.ALICE,
                "participant_key": TestPrivateKeys.BOB,
            },
            expected_result={"status": "redeemed"},
        )

    @staticmethod
    def storage_lifecycle() -> Scenario:
        """存储生命周期测试场景"""
        return Scenario(
            name="storage_lifecycle",
            description="测试内容上传、Pin、获取、删除的完整流程",
            data={
                "content": StorageFactory.create_test_json(),
                "metadata": StorageFactory.create_metadata(),
            },
            expected_result={"pinned": True, "retrieved": True},
        )
