"""
交易构造与签名模块测试
========================
覆盖正常流程与异常流程场景

测试分类:
1. 交易构造测试 (TransactionBuilder)
2. 多签管理测试 (MultiSigManager)
3. 签名服务测试 (SigningService)
4. Gas优化测试 (GasOptimizer)
"""

import pytest
import asyncio
from eth_account import Account
from eth_utils import to_checksum_address

from wallethub.modules.transaction import (
    TransactionBuilder,
    EIP1559Transaction,
    LegacyTransaction,
    MultiSigManager,
    SigningService,
    GasOptimizer,
)
from wallethub.core import (
    TransactionError,
    SigningError,
    MultiSigStatus,
)
from tests.test_factories import (
    TestAddresses,
    TestPrivateKeys,
    TransactionFactory,
)


# ============================================================================
# 1. 交易构造测试
# ============================================================================

class TestTransactionBuilder:
    """交易构造器测试"""

    def test_create_legacy_transfer_transaction(self, mock_settings):
        """测试: 构造Legacy格式转账交易 - 正常流程"""
        builder = TransactionBuilder(chain="ethereum")
        tx_data = TransactionFactory.create_simple_transfer()

        tx = builder.build_legacy(
            to_address=tx_data["to_address"],
            value=tx_data["value"],
            from_address=tx_data["from_address"],
            gas_limit=tx_data["gas_limit"],
            gas_price=tx_data["gas_price"],
        )

        assert isinstance(tx, LegacyTransaction)
        assert tx.chain == "ethereum"
        assert tx.to_address == tx_data["to_address"]
        assert tx.value == tx_data["value"]
        assert tx.gas_limit == tx_data["gas_limit"]
        assert tx.gas_price == tx_data["gas_price"]
        assert tx.tx_id is not None
        assert len(tx.tx_id) > 0

    def test_create_eip1559_transfer_transaction(self, mock_settings):
        """测试: 构造EIP-1559格式转账交易 - 正常流程"""
        builder = TransactionBuilder(chain="ethereum")
        tx_data = TransactionFactory.create_eip1559_transfer()

        tx = builder.build_eip1559(
            to_address=tx_data["to_address"],
            value=tx_data["value"],
            from_address=tx_data["from_address"],
            gas_limit=tx_data["gas_limit"],
            max_fee_per_gas=tx_data["max_fee_per_gas"],
            max_priority_fee_per_gas=tx_data["max_priority_fee_per_gas"],
        )

        assert isinstance(tx, EIP1559Transaction)
        assert tx.max_fee_per_gas == tx_data["max_fee_per_gas"]
        assert tx.max_priority_fee_per_gas == tx_data["max_priority_fee_per_gas"]

    def test_create_contract_deployment_transaction(self, mock_settings):
        """测试: 构造合约部署交易 - 正常流程"""
        builder = TransactionBuilder(chain="ethereum")
        tx_data = TransactionFactory.create_contract_deployment()

        tx = builder.build_contract_deployment(
            bytecode=tx_data["data"][2:],
            from_address=tx_data["from_address"],
            gas_limit=tx_data["gas_limit"],
            eip1559=True,
            max_fee_per_gas=tx_data["max_fee_per_gas"],
            max_priority_fee_per_gas=tx_data["max_priority_fee_per_gas"],
        )

        assert tx.to_address is None
        assert tx.data is not None
        assert len(tx.data) > 2

    def test_legacy_transaction_to_dict(self, mock_settings):
        """测试: Legacy交易转换为字典格式"""
        builder = TransactionBuilder(chain="ethereum")
        tx_data = TransactionFactory.create_simple_transfer()

        tx = builder.build_legacy(**tx_data)
        tx_dict = tx.to_dict()

        assert "to" in tx_dict
        assert "value" in tx_dict
        assert "gas" in tx_dict
        assert "gasPrice" in tx_dict
        assert "chainId" in tx_dict
        assert tx_dict["chainId"] == 1

    def test_eip1559_transaction_to_dict(self, mock_settings):
        """测试: EIP-1559交易转换为字典格式"""
        builder = TransactionBuilder(chain="ethereum")
        tx_data = TransactionFactory.create_eip1559_transfer()

        tx = builder.build_eip1559(**tx_data)
        tx_dict = tx.to_dict()

        assert tx_dict["type"] == 2
        assert "maxFeePerGas" in tx_dict
        assert "maxPriorityFeePerGas" in tx_dict

    @pytest.mark.parametrize("invalid_case", TransactionFactory.create_invalid_transactions())
    def test_invalid_transaction_construction(self, mock_settings, invalid_case):
        """测试: 各种异常交易数据 - 异常流程"""
        builder = TransactionBuilder(chain="ethereum")
        data = invalid_case["data"]

        with pytest.raises(TransactionError) as exc_info:
            if "max_fee_per_gas" in data:
                builder.build_eip1559(**data)
            else:
                builder.build_legacy(**data)

        assert invalid_case["expected_error"] in str(exc_info.value)

    def test_transaction_validation_positive(self, mock_settings):
        """测试: 合法交易验证通过 - 正常流程"""
        builder = TransactionBuilder(chain="ethereum")
        tx_data = TransactionFactory.create_simple_transfer()
        tx = builder.build_legacy(**tx_data)

        assert tx.validate() is None

    def test_transaction_with_access_list(self, mock_settings):
        """测试: 带有访问列表的交易 - 正常流程"""
        builder = TransactionBuilder(chain="ethereum")
        access_list = [
            {
                "address": TestAddresses.TOKEN_CONTRACT,
                "storageKeys": [
                    "0x" + "0" * 63 + "1",
                    "0x" + "0" * 63 + "2",
                ],
            }
        ]

        tx = builder.build_legacy(
            to_address=TestAddresses.BOB,
            value=1000,
            gas_limit=21000,
            gas_price=20000000000,
            access_list=access_list,
        )

        assert tx.access_list == access_list
        tx_dict = tx.to_dict()
        assert "accessList" in tx_dict


# ============================================================================
# 2. 多签管理测试
# ============================================================================

class TestMultiSigManager:
    """多签管理器测试"""

    def test_create_multisig_wallet(self, mock_settings):
        """测试: 创建多签钱包 - 正常流程"""
        manager = MultiSigManager()
        config = TransactionFactory.create_multi_sig_wallet_config()

        wallet = manager.create_wallet(**config)

        assert wallet.wallet_id is not None
        assert wallet.name == config["name"]
        assert wallet.chain == config["chain"]
        assert len(wallet.owners) == 3
        assert wallet.threshold == 2
        assert TestAddresses.ALICE in wallet.owners

    def test_create_multisig_invalid_threshold(self, mock_settings):
        """测试: 创建多签钱包时阈值超过所有者数量 - 异常流程"""
        manager = MultiSigManager()
        config = TransactionFactory.create_multi_sig_wallet_config()
        config["threshold"] = 5

        with pytest.raises(SigningError) as exc_info:
            manager.create_wallet(**config)

        assert "Threshold cannot exceed number of owners" in str(exc_info.value)

    def test_create_multisig_zero_threshold(self, mock_settings):
        """测试: 创建多签钱包时阈值为0 - 异常流程"""
        manager = MultiSigManager()
        config = TransactionFactory.create_multi_sig_wallet_config()
        config["threshold"] = 0

        with pytest.raises(SigningError) as exc_info:
            manager.create_wallet(**config)

        assert "Threshold must be greater than 0" in str(exc_info.value)

    def test_create_proposal(self, mock_settings):
        """测试: 创建多签提案 - 正常流程"""
        manager = MultiSigManager()
        config = TransactionFactory.create_multi_sig_wallet_config()
        wallet = manager.create_wallet(**config)
        proposal_data = TransactionFactory.create_multi_sig_proposal()

        proposal = manager.create_proposal(
            wallet_id=wallet.wallet_id,
            **proposal_data,
        )

        assert proposal.proposal_id is not None
        assert proposal.wallet_id == wallet.wallet_id
        assert proposal.status == MultiSigStatus.PENDING
        assert len(proposal.signatures) == 0

    def test_create_proposal_unknown_wallet(self, mock_settings):
        """测试: 为不存在的钱包创建提案 - 异常流程"""
        manager = MultiSigManager()
        proposal_data = TransactionFactory.create_multi_sig_proposal()

        with pytest.raises(SigningError) as exc_info:
            manager.create_proposal(
                wallet_id="non_existent_wallet",
                **proposal_data,
            )

        assert "not found" in str(exc_info.value).lower()

    def test_sign_proposal(self, mock_settings):
        """测试: 签名提案 - 正常流程"""
        manager = MultiSigManager()
        config = TransactionFactory.create_multi_sig_wallet_config()
        wallet = manager.create_wallet(**config)
        proposal_data = TransactionFactory.create_multi_sig_proposal()
        proposal = manager.create_proposal(wallet_id=wallet.wallet_id, **proposal_data)

        signed_proposal = manager.sign_proposal(
            proposal_id=proposal.proposal_id,
            owner_address=TestAddresses.ALICE,
            private_key=TestPrivateKeys.ALICE,
        )

        assert len(signed_proposal.signatures) == 1
        assert TestAddresses.ALICE in signed_proposal.signatures
        assert signed_proposal.status == MultiSigStatus.PARTIALLY_SIGNED

    def test_duplicate_signature(self, mock_settings):
        """测试: 重复签名 - 异常流程"""
        manager = MultiSigManager()
        config = TransactionFactory.create_multi_sig_wallet_config()
        wallet = manager.create_wallet(**config)
        proposal_data = TransactionFactory.create_multi_sig_proposal()
        proposal = manager.create_proposal(wallet_id=wallet.wallet_id, **proposal_data)

        manager.sign_proposal(
            proposal_id=proposal.proposal_id,
            owner_address=TestAddresses.ALICE,
            private_key=TestPrivateKeys.ALICE,
        )

        with pytest.raises(SigningError) as exc_info:
            manager.sign_proposal(
                proposal_id=proposal.proposal_id,
                owner_address=TestAddresses.ALICE,
                private_key=TestPrivateKeys.ALICE,
            )

        assert "already signed" in str(exc_info.value).lower()

    def test_non_owner_cannot_sign(self, mock_settings):
        """测试: 非所有者尝试签名 - 异常流程"""
        manager = MultiSigManager()
        config = TransactionFactory.create_multi_sig_wallet_config()
        wallet = manager.create_wallet(**config)
        proposal_data = TransactionFactory.create_multi_sig_proposal()
        proposal = manager.create_proposal(wallet_id=wallet.wallet_id, **proposal_data)

        with pytest.raises(SigningError) as exc_info:
            manager.sign_proposal(
                proposal_id=proposal.proposal_id,
                owner_address=TestAddresses.DAVE,
                private_key=TestPrivateKeys.ALICE,
            )

        assert "not an owner" in str(exc_info.value).lower()

    def test_full_signing_threshold_reached(self, mock_settings):
        """测试: 达到阈值后状态变为完全签名 - 正常流程"""
        manager = MultiSigManager()
        config = TransactionFactory.create_multi_sig_wallet_config()
        wallet = manager.create_wallet(**config)
        proposal_data = TransactionFactory.create_multi_sig_proposal()
        proposal = manager.create_proposal(wallet_id=wallet.wallet_id, **proposal_data)

        manager.sign_proposal(proposal.proposal_id, TestAddresses.ALICE, TestPrivateKeys.ALICE)
        final_proposal = manager.sign_proposal(
            proposal.proposal_id, TestAddresses.BOB, TestPrivateKeys.BOB
        )

        assert final_proposal.status == MultiSigStatus.FULLY_SIGNED
        assert len(final_proposal.signatures) == 2

    def test_revoke_signature(self, mock_settings):
        """测试: 撤销签名 - 正常流程"""
        manager = MultiSigManager()
        config = TransactionFactory.create_multi_sig_wallet_config()
        wallet = manager.create_wallet(**config)
        proposal_data = TransactionFactory.create_multi_sig_proposal()
        proposal = manager.create_proposal(wallet_id=wallet.wallet_id, **proposal_data)

        manager.sign_proposal(proposal.proposal_id, TestAddresses.ALICE, TestPrivateKeys.ALICE)
        proposal.revoke_signature(TestAddresses.ALICE)

        assert len(proposal.signatures) == 0
        assert proposal.status == MultiSigStatus.PENDING

    def test_get_executable_proposals(self, mock_settings):
        """测试: 获取可执行的提案列表 - 正常流程"""
        manager = MultiSigManager()
        config = TransactionFactory.create_multi_sig_wallet_config()
        wallet = manager.create_wallet(**config)

        for i in range(3):
            proposal_data = TransactionFactory.create_multi_sig_proposal()
            proposal_data["value"] = (i + 1) * 1000
            proposal = manager.create_proposal(wallet_id=wallet.wallet_id, **proposal_data)

            if i < 2:
                manager.sign_proposal(proposal.proposal_id, TestAddresses.ALICE, TestPrivateKeys.ALICE)
                manager.sign_proposal(proposal.proposal_id, TestAddresses.BOB, TestPrivateKeys.BOB)

        executable = manager.get_executable_proposals(wallet.wallet_id)
        assert len(executable) == 2


# ============================================================================
# 3. 签名服务测试
# ============================================================================

class TestSigningService:
    """签名服务测试"""

    def test_import_private_key(self, mock_settings):
        """测试: 导入私钥 - 正常流程"""
        service = SigningService()

        entry = service.import_private_key(
            private_key=TestPrivateKeys.ALICE,
            chain="ethereum",
        )

        assert entry.key_id is not None
        assert entry.address.lower() == TestAddresses.ALICE.lower()
        assert entry.chain == "ethereum"

    def test_create_signer(self, mock_settings):
        """测试: 创建签名器 - 正常流程"""
        service = SigningService()
        entry = service.import_private_key(TestPrivateKeys.ALICE, "ethereum")

        signer = service.create_signer(entry.key_id)

        assert signer.address.lower() == TestAddresses.ALICE.lower()

    def test_sign_transaction(self, mock_settings):
        """测试: 签名交易 - 正常流程"""
        from wallethub.modules.transaction import TransactionBuilder

        service = SigningService()
        entry = service.import_private_key(TestPrivateKeys.ALICE, "ethereum")
        signer = service.create_signer(entry.key_id)

        builder = TransactionBuilder(chain="ethereum")
        tx = builder.build_legacy(
            to_address=TestAddresses.BOB,
            value=1000,
            gas_limit=21000,
            gas_price=20000000000,
            nonce=0,
        )

        signed = signer.sign_transaction(tx.to_dict())

        assert signed.rawTransaction is not None
        assert signed.hash is not None

    def test_sign_message(self, mock_settings):
        """测试: 签名消息 - 正常流程"""
        service = SigningService()
        entry = service.import_private_key(TestPrivateKeys.ALICE, "ethereum")
        signer = service.create_signer(entry.key_id)

        message = "Hello, WalletHub!"
        signature = signer.sign_message(message)

        assert signature is not None
        assert signature.startswith("0x")
        assert len(signature) == 132

    def test_verify_signature(self, mock_settings):
        """测试: 验证签名 - 正常流程"""
        service = SigningService()
        entry = service.import_private_key(TestPrivateKeys.ALICE, "ethereum")
        signer = service.create_signer(entry.key_id)

        message = "Test message for verification"
        signature = signer.sign_message(message)

        is_valid = SigningService.verify_signature(
            message=message,
            signature=signature,
            expected_address=TestAddresses.ALICE,
        )

        assert is_valid is True

    def test_verify_signature_wrong_address(self, mock_settings):
        """测试: 使用错误地址验证签名 - 异常流程"""
        service = SigningService()
        entry = service.import_private_key(TestPrivateKeys.ALICE, "ethereum")
        signer = service.create_signer(entry.key_id)

        message = "Test message"
        signature = signer.sign_message(message)

        is_valid = SigningService.verify_signature(
            message=message,
            signature=signature,
            expected_address=TestAddresses.BOB,
        )

        assert is_valid is False

    def test_generate_new_key(self, mock_settings):
        """测试: 生成新私钥 - 正常流程"""
        private_key, address = SigningService.generate_new_key()

        assert private_key is not None
        assert private_key.startswith("0x")
        assert len(private_key) == 66

        assert address is not None
        assert address.startswith("0x")
        assert len(address) == 42

        account = Account.from_key(private_key)
        assert account.address.lower() == address.lower()

    def test_list_keys(self, mock_settings):
        """测试: 列出所有密钥 - 正常流程"""
        service = SigningService()

        service.import_private_key(TestPrivateKeys.ALICE, "ethereum")
        service.import_private_key(TestPrivateKeys.BOB, "ethereum")

        keys = service.list_keys()
        assert len(keys) == 2

    def test_delete_key(self, mock_settings):
        """测试: 删除密钥 - 正常流程"""
        service = SigningService()
        entry = service.import_private_key(TestPrivateKeys.ALICE, "ethereum")

        assert len(service.list_keys()) == 1
        service.delete_key(entry.key_id)
        assert len(service.list_keys()) == 0

    def test_delete_key_removes_signer(self, mock_settings):
        """测试: 删除密钥同时移除签名器 - 正常流程"""
        service = SigningService()
        entry = service.import_private_key(TestPrivateKeys.ALICE, "ethereum")
        service.create_signer(entry.key_id)

        assert service.get_signer(entry.key_id) is not None
        service.delete_key(entry.key_id)
        assert service.get_signer(entry.key_id) is None


# ============================================================================
# 4. Gas优化测试
# ============================================================================

class TestGasOptimizer:
    """Gas优化器测试"""

    def test_optimize_gas_fees_standard(self, mock_settings):
        """测试: 标准速度Gas费用优化 - 正常流程"""
        optimizer = GasOptimizer(history_window=50)

        base_fee = 20000000000
        priority_fees = {
            "slow": 1000000000,
            "standard": 2000000000,
            "fast": 3000000000,
            "urgent": 5000000000,
        }

        result = optimizer.optimize_gas_fees(
            chain="ethereum",
            current_base_fee=base_fee,
            current_priority_fees=priority_fees,
            estimated_gas=21000,
            urgency="standard",
        )

        assert result.speed == "standard"
        assert result.max_fee_per_gas is not None
        assert result.max_priority_fee_per_gas is not None
        assert result.estimated_cost > 0
        assert result.recommended is True

    def test_generate_all_speed_options(self, mock_settings):
        """测试: 生成所有速度档位的Gas预估 - 正常流程"""
        optimizer = GasOptimizer(history_window=50)

        base_fee = 20000000000
        priority_fees = {
            "slow": 1000000000,
            "standard": 2000000000,
            "fast": 3000000000,
            "urgent": 5000000000,
        }

        options = optimizer.generate_all_options(
            chain="ethereum",
            current_base_fee=base_fee,
            current_priority_fees=priority_fees,
            estimated_gas=21000,
        )

        assert len(options) == 4
        speeds = [opt.speed for opt in options]
        assert "slow" in speeds
        assert "standard" in speeds
        assert "fast" in speeds
        assert "urgent" in speeds

        standard_option = next(opt for opt in options if opt.speed == "standard")
        assert standard_option.recommended is True

    def test_urgent_speed_higher_fees(self, mock_settings):
        """测试: 紧急速度档位费用更高 - 正常流程"""
        optimizer = GasOptimizer(history_window=50)

        base_fee = 20000000000
        priority_fees = {
            "slow": 1000000000,
            "standard": 2000000000,
            "fast": 3000000000,
            "urgent": 5000000000,
        }

        slow = optimizer.optimize_gas_fees("ethereum", base_fee, priority_fees, 21000, "slow")
        urgent = optimizer.optimize_gas_fees("ethereum", base_fee, priority_fees, 21000, "urgent")

        assert urgent.max_fee_per_gas > slow.max_fee_per_gas
        assert urgent.estimated_cost > slow.estimated_cost

    def test_suggest_gas_limit(self, mock_settings):
        """测试: 建议Gas限制 - 正常流程"""
        optimizer = GasOptimizer()

        estimated = 21000
        suggested = optimizer.suggest_gas_limit(estimated, tx_type="transfer")
        assert suggested == 21000

        estimated = 100000
        suggested = optimizer.suggest_gas_limit(estimated, tx_type="contract_call")
        assert suggested == 120000

        estimated = 500000
        suggested = optimizer.suggest_gas_limit(estimated, tx_type="contract_deployment")
        assert suggested == 750000

    def test_calculate_fee(self, mock_settings):
        """测试: 计算交易费用 - 正常流程"""
        optimizer = GasOptimizer()

        fee = optimizer.calculate_fee(gas_limit=21000, gas_price=20000000000)
        assert fee == 21000 * 20000000000

        fee = optimizer.calculate_fee(gas_limit=21000, max_fee_per_gas=30000000000)
        assert fee == 21000 * 30000000000

    def test_volatility_calculation(self, mock_settings):
        """测试: 计算波动率 - 正常流程"""
        optimizer = GasOptimizer(history_window=100)

        for i in range(15):
            base_fee = 20000000000 + i * 100000000
            optimizer.record_block_prices(
                chain="ethereum",
                block_number=18000000 + i,
                base_fee=base_fee,
                priority_fee_low=1000000000,
                priority_fee_med=2000000000,
                priority_fee_high=3000000000,
            )

        volatility = optimizer.get_volatility("ethereum")
        assert volatility >= 0

    def test_max_acceptable_fee_limit(self, mock_settings):
        """测试: 最大可接受费用限制 - 正常流程"""
        optimizer = GasOptimizer(history_window=50)

        base_fee = 20000000000
        priority_fees = {
            "slow": 1000000000,
            "standard": 2000000000,
            "fast": 3000000000,
            "urgent": 5000000000,
        }

        max_fee = 25000000000
        result = optimizer.optimize_gas_fees(
            chain="ethereum",
            current_base_fee=base_fee,
            current_priority_fees=priority_fees,
            estimated_gas=21000,
            urgency="urgent",
            max_acceptable_fee=max_fee,
        )

        assert result.max_fee_per_gas <= max_fee

    def test_calculate_fee_requires_params(self, mock_settings):
        """测试: 计算费用时缺少必要参数 - 异常流程"""
        optimizer = GasOptimizer()

        with pytest.raises(ValueError):
            optimizer.calculate_fee(gas_limit=21000)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
