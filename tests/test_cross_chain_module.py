"""
资产跨链桥接模块测试
==================
重点测试：
1. 并发操作安全性 - 验证高并发下状态管理的正确性
2. 状态机流转正确性 - 验证状态转换的原子性
3. 异常场景处理 - 验证错误情况下的系统稳定性
"""

import asyncio
import pytest
import threading
from typing import List, Dict, Any
from unittest.mock import MagicMock, patch

from wallethub.core import CrossChainStatus, CrossChainError
from wallethub.modules.cross_chain import CrossChainBridge, BridgeType, BridgeTransfer
from wallethub.modules.cross_chain.message_verifier import MessageVerifier
from wallethub.modules.cross_chain.atomic_swap import AtomicSwapManager
from tests.test_factories import CrossChainFactory, TestAddresses, TestDataGenerator


class TestCrossChainBridge:
    """跨链桥接核心功能测试"""

    @pytest.fixture
    def bridge(self):
        """创建配置好的跨链桥接实例"""
        bridge = CrossChainBridge()
        bridge.register_chain_bridge("ethereum", MagicMock())
        bridge.register_chain_bridge("polygon", MagicMock())
        bridge.register_chain_bridge("bsc", MagicMock())
        return bridge

    @pytest.fixture
    def bridge_with_validators(self, bridge):
        """创建带验证者的跨链桥接实例"""
        for i in range(5):
            bridge.register_validator(TestDataGenerator.random_address())
        return bridge

    def test_initiate_transfer_success(self, bridge):
        """测试正常发起跨链转账"""
        transfer_data = CrossChainFactory.create_bridge_transfer()
        transfer = bridge.initiate_transfer(**transfer_data)

        assert transfer is not None
        assert transfer.transfer_id is not None
        assert transfer.status == CrossChainStatus.INITIATED
        assert transfer.source_chain == transfer_data["source_chain"]
        assert transfer.target_chain == transfer_data["target_chain"]
        assert transfer.amount == transfer_data["amount"]
        assert transfer.message_hash is not None

    def test_initiate_transfer_negative_amount(self, bridge):
        """测试负金额转账异常"""
        transfer_data = CrossChainFactory.create_bridge_transfer()
        transfer_data["amount"] = -100

        with pytest.raises(CrossChainError, match="Amount must be greater than 0"):
            bridge.initiate_transfer(**transfer_data)

    def test_initiate_transfer_zero_amount(self, bridge):
        """测试零金额转账异常"""
        transfer_data = CrossChainFactory.create_bridge_transfer()
        transfer_data["amount"] = 0

        with pytest.raises(CrossChainError, match="Amount must be greater than 0"):
            bridge.initiate_transfer(**transfer_data)

    def test_initiate_transfer_unknown_source_chain(self, bridge):
        """测试未知源链异常"""
        transfer_data = CrossChainFactory.create_bridge_transfer()
        transfer_data["source_chain"] = "unknown_chain"

        with pytest.raises(CrossChainError, match="Bridge not configured for source chain"):
            bridge.initiate_transfer(**transfer_data)

    def test_initiate_transfer_unknown_target_chain(self, bridge):
        """测试未知目标链异常"""
        transfer_data = CrossChainFactory.create_bridge_transfer()
        transfer_data["target_chain"] = "unknown_chain"

        with pytest.raises(CrossChainError, match="Bridge not configured for target chain"):
            bridge.initiate_transfer(**transfer_data)

    def test_state_transition_lock_success(self, bridge):
        """测试状态流转：INITIATED -> LOCKED"""
        transfer_data = CrossChainFactory.create_bridge_transfer()
        transfer = bridge.initiate_transfer(**transfer_data)

        tx_hash = TestDataGenerator.random_tx_hash()
        locked_transfer = bridge.confirm_source_lock(
            transfer_id=transfer.transfer_id,
            source_tx_hash=tx_hash,
            proof_data={"merkle_proof": "test_proof"},
        )

        assert locked_transfer.status == CrossChainStatus.LOCKED
        assert locked_transfer.source_tx_hash == tx_hash
        assert "merkle_proof" in locked_transfer.proof_data

    def test_state_transition_invalid_lock_from_wrong_state(self, bridge):
        """测试错误状态下尝试锁定的异常"""
        transfer_data = CrossChainFactory.create_bridge_transfer()
        transfer = bridge.initiate_transfer(**transfer_data)

        bridge.fail_transfer(transfer.transfer_id, "test failure")

        with pytest.raises(CrossChainError, match="Invalid status transition"):
            bridge.confirm_source_lock(
                transfer_id=transfer.transfer_id,
                source_tx_hash=TestDataGenerator.random_tx_hash(),
            )

    def test_state_transition_verify_success(self, bridge_with_validators):
        """测试状态流转：LOCKED -> VERIFIED"""
        bridge = bridge_with_validators
        transfer_data = CrossChainFactory.create_bridge_transfer()
        transfer = bridge.initiate_transfer(**transfer_data)

        bridge.confirm_source_lock(
            transfer_id=transfer.transfer_id,
            source_tx_hash=TestDataGenerator.random_tx_hash(),
        )

        signatures = ["sig_" + str(i) for i in range(4)]
        verified_transfer = bridge.verify_message(
            transfer_id=transfer.transfer_id,
            validator_signatures=signatures,
        )

        assert verified_transfer.status == CrossChainStatus.VERIFIED
        assert len(verified_transfer.proof_data["signatures"]) == 4

    def test_state_transition_verify_insufficient_signatures(self, bridge_with_validators):
        """测试验证时签名不足的异常"""
        bridge = bridge_with_validators
        transfer_data = CrossChainFactory.create_bridge_transfer()
        transfer = bridge.initiate_transfer(**transfer_data)

        bridge.confirm_source_lock(
            transfer_id=transfer.transfer_id,
            source_tx_hash=TestDataGenerator.random_tx_hash(),
        )

        signatures = ["sig_1", "sig_2"]
        with pytest.raises(CrossChainError, match="Insufficient signatures"):
            bridge.verify_message(
                transfer_id=transfer.transfer_id,
                validator_signatures=signatures,
            )

    def test_state_transition_full_lifecycle(self, bridge_with_validators):
        """测试完整状态流转生命周期"""
        bridge = bridge_with_validators
        transfer_data = CrossChainFactory.create_bridge_transfer()

        transfer = bridge.initiate_transfer(**transfer_data)
        assert transfer.status == CrossChainStatus.INITIATED

        transfer = bridge.confirm_source_lock(
            transfer_id=transfer.transfer_id,
            source_tx_hash=TestDataGenerator.random_tx_hash(),
        )
        assert transfer.status == CrossChainStatus.LOCKED

        transfer = bridge.verify_message(
            transfer_id=transfer.transfer_id,
            validator_signatures=["sig_" + str(i) for i in range(4)],
        )
        assert transfer.status == CrossChainStatus.VERIFIED

        transfer = bridge.mint_target(
            transfer_id=transfer.transfer_id,
            target_tx_hash=TestDataGenerator.random_tx_hash(),
        )
        assert transfer.status == CrossChainStatus.MINTED

        transfer = bridge.complete_transfer(transfer_id=transfer.transfer_id)
        assert transfer.status == CrossChainStatus.COMPLETED

    def test_fail_transfer_from_any_state(self, bridge):
        """测试从任意状态标记失败"""
        transfer_data = CrossChainFactory.create_bridge_transfer()
        transfer = bridge.initiate_transfer(**transfer_data)

        failed_transfer = bridge.fail_transfer(
            transfer_id=transfer.transfer_id,
            error_message="Test error message",
        )

        assert failed_transfer.status == CrossChainStatus.FAILED
        assert failed_transfer.proof_data["error"] == "Test error message"

    def test_list_transfers_filtering(self, bridge):
        """测试转账列表过滤功能"""
        for i in range(5):
            transfer_data = CrossChainFactory.create_bridge_transfer()
            if i % 2 == 0:
                transfer_data["target_chain"] = "bsc"
            bridge.initiate_transfer(**transfer_data)

        polygon_transfers = bridge.list_transfers(target_chain="polygon")
        bsc_transfers = bridge.list_transfers(target_chain="bsc")

        assert len(polygon_transfers) == 2
        assert len(bsc_transfers) == 3
        assert all(t.target_chain == "polygon" for t in polygon_transfers)
        assert all(t.target_chain == "bsc" for t in bsc_transfers)

    def test_get_nonexistent_transfer(self, bridge):
        """测试获取不存在的转账"""
        transfer = bridge.get_transfer("nonexistent_id")
        assert transfer is None

        with pytest.raises(CrossChainError, match="not found"):
            bridge._get_transfer("nonexistent_id")


class TestCrossChainConcurrency:
    """跨链并发安全性测试"""

    @pytest.fixture
    def bridge(self):
        """创建配置好的跨链桥接实例"""
        bridge = CrossChainBridge()
        bridge.register_chain_bridge("ethereum", MagicMock())
        bridge.register_chain_bridge("polygon", MagicMock())
        bridge.register_chain_bridge("bsc", MagicMock())
        return bridge

    @pytest.mark.asyncio
    async def test_concurrent_initiate_transfers(self, bridge):
        """测试并发创建跨链转账的安全性"""
        transfers_data = CrossChainFactory.create_concurrent_transfers(count=20)

        async def initiate_transfer(data):
            return bridge.initiate_transfer(**data)

        tasks = [initiate_transfer(data) for data in transfers_data]
        results = await asyncio.gather(*tasks)

        assert len(results) == 20
        transfer_ids = [t.transfer_id for t in results]
        assert len(set(transfer_ids)) == 20

        amounts = [t.amount for t in results]
        expected_amounts = [(i + 1) * 1000000000000000000 for i in range(20)]
        assert sorted(amounts) == sorted(expected_amounts)

    @pytest.mark.asyncio
    async def test_concurrent_state_transitions(self, bridge):
        """测试并发状态流转的原子性"""
        transfer_data = CrossChainFactory.create_bridge_transfer()
        transfer = bridge.initiate_transfer(**transfer_data)

        async def lock_transfer():
            try:
                return bridge.confirm_source_lock(
                    transfer_id=transfer.transfer_id,
                    source_tx_hash=TestDataGenerator.random_tx_hash(),
                )
            except CrossChainError:
                return None

        tasks = [lock_transfer() for _ in range(10)]
        results = await asyncio.gather(*tasks)

        success_count = sum(1 for r in results if r is not None and r.status == CrossChainStatus.LOCKED)
        assert success_count == 1

        updated_transfer = bridge.get_transfer(transfer.transfer_id)
        assert updated_transfer.status == CrossChainStatus.LOCKED

    @pytest.mark.asyncio
    async def test_concurrent_mixed_operations(self, bridge):
        """测试并发混合操作的安全性"""
        for i in range(5):
            data = CrossChainFactory.create_bridge_transfer()
            bridge.initiate_transfer(**data)

        transfers = bridge.list_transfers()
        assert len(transfers) == 5

        async def random_operation(transfer: BridgeTransfer):
            ops = ["lock", "fail", "get"]
            op = ops[hash(transfer.transfer_id) % 3]
            try:
                if op == "lock":
                    return bridge.confirm_source_lock(
                        transfer_id=transfer.transfer_id,
                        source_tx_hash=TestDataGenerator.random_tx_hash(),
                    )
                elif op == "fail":
                    return bridge.fail_transfer(
                        transfer_id=transfer.transfer_id,
                        error_message="Concurrent failure",
                    )
                else:
                    return bridge.get_transfer(transfer.transfer_id)
            except CrossChainError:
                return None

        tasks = [random_operation(t) for t in transfers for _ in range(3)]
        results = await asyncio.gather(*tasks)

        assert len(results) == 15
        for t in transfers:
            current = bridge.get_transfer(t.transfer_id)
            assert current is not None
            assert current.status in [
                CrossChainStatus.INITIATED,
                CrossChainStatus.LOCKED,
                CrossChainStatus.FAILED,
            ]

    def test_thread_safe_transfer_creation(self, bridge):
        """测试多线程环境下转账创建的线程安全性"""
        created_transfers = []
        errors = []

        def create_transfer():
            try:
                data = CrossChainFactory.create_bridge_transfer()
                transfer = bridge.initiate_transfer(**data)
                created_transfers.append(transfer.transfer_id)
            except Exception as e:
                errors.append(str(e))

        threads = [threading.Thread(target=create_transfer) for _ in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(errors) == 0
        assert len(created_transfers) == 10
        assert len(set(created_transfers)) == 10

    @pytest.mark.asyncio
    async def test_concurrent_list_and_modify(self, bridge):
        """测试并发列表查询与修改操作"""
        for i in range(10):
            data = CrossChainFactory.create_bridge_transfer()
            bridge.initiate_transfer(**data)

        async def list_operation():
            return bridge.list_transfers()

        async def modify_operation():
            transfers = bridge.list_transfers(status=CrossChainStatus.INITIATED)
            if transfers:
                try:
                    bridge.fail_transfer(
                        transfer_id=transfers[0].transfer_id,
                        error_message="Concurrent modification",
                    )
                    return True
                except CrossChainError:
                    return False
            return None

        list_tasks = [list_operation() for _ in range(5)]
        modify_tasks = [modify_operation() for _ in range(5)]
        all_tasks = list_tasks + modify_tasks

        results = await asyncio.gather(*all_tasks)

        list_results = [r for r in results[:5] if isinstance(r, list)]
        assert len(list_results) == 5
        for lst in list_results:
            assert len(lst) <= 10

        remaining = bridge.list_transfers(status=CrossChainStatus.INITIATED)
        assert len(remaining) >= 5


class TestMessageVerifier:
    """跨链消息验证器测试"""

    @pytest.fixture
    def verifier(self):
        """创建消息验证器实例"""
        return MessageVerifier(required_signatures=2)

    @pytest.fixture
    def verifier_with_validators(self, verifier):
        """创建带信任验证者的消息验证器"""
        verifier.add_trusted_validator(TestAddresses.ALICE)
        verifier.add_trusted_validator(TestAddresses.BOB)
        verifier.add_trusted_validator(TestAddresses.CHARLIE)
        return verifier

    def test_add_trusted_validator(self, verifier):
        """测试添加信任验证者"""
        verifier.add_trusted_validator(TestAddresses.ALICE)
        validators = verifier.get_trusted_validators()
        assert TestAddresses.ALICE.lower() in validators
        assert len(validators) == 1

    def test_add_duplicate_validator(self, verifier):
        """测试添加重复验证者"""
        verifier.add_trusted_validator(TestAddresses.ALICE)
        verifier.add_trusted_validator(TestAddresses.ALICE)
        validators = verifier.get_trusted_validators()
        assert len(validators) == 1

    def test_remove_trusted_validator(self, verifier):
        """测试移除信任验证者"""
        verifier.add_trusted_validator(TestAddresses.ALICE)
        verifier.remove_trusted_validator(TestAddresses.ALICE)
        validators = verifier.get_trusted_validators()
        assert len(validators) == 0

    def test_sign_and_verify_message(self, verifier_with_validators):
        """测试消息签名和验证"""
        from wallethub.modules.cross_chain.message_verifier import CrossChainMessage
        from tests.test_factories import TestPrivateKeys

        message = CrossChainMessage(**CrossChainFactory.create_cross_chain_message())

        signature = verifier_with_validators.sign_message(message, TestPrivateKeys.ALICE)
        is_valid = verifier_with_validators.verify_signature(
            message, signature, TestAddresses.ALICE
        )
        assert is_valid is True

        is_valid = verifier_with_validators.verify_signature(
            message, signature, TestAddresses.BOB
        )
        assert is_valid is False

    def test_add_signature_to_message(self, verifier_with_validators):
        """测试向消息添加签名"""
        from wallethub.modules.cross_chain.message_verifier import CrossChainMessage
        from tests.test_factories import TestPrivateKeys

        message = CrossChainMessage(**CrossChainFactory.create_cross_chain_message())
        signature = verifier_with_validators.sign_message(message, TestPrivateKeys.ALICE)

        updated_message = verifier_with_validators.add_signature(
            message, signature, TestAddresses.ALICE
        )
        assert len(updated_message.signatures) == 1
        assert updated_message.signatures[0]["signer"] == TestAddresses.ALICE

    def test_add_signature_from_untrusted_validator(self, verifier):
        """测试非信任验证者签名异常"""
        from wallethub.modules.cross_chain.message_verifier import CrossChainMessage
        from tests.test_factories import TestPrivateKeys

        message = CrossChainMessage(**CrossChainFactory.create_cross_chain_message())
        signature = verifier.sign_message(message, TestPrivateKeys.ALICE)

        with pytest.raises(CrossChainError, match="not a trusted validator"):
            verifier.add_signature(message, signature, TestAddresses.ALICE)

    def test_add_duplicate_signature(self, verifier_with_validators):
        """测试重复签名异常"""
        from wallethub.modules.cross_chain.message_verifier import CrossChainMessage
        from tests.test_factories import TestPrivateKeys

        message = CrossChainMessage(**CrossChainFactory.create_cross_chain_message())
        signature = verifier_with_validators.sign_message(message, TestPrivateKeys.ALICE)

        verifier_with_validators.add_signature(message, signature, TestAddresses.ALICE)

        with pytest.raises(CrossChainError, match="has already signed"):
            verifier_with_validators.add_signature(message, signature, TestAddresses.ALICE)

    def test_message_verification_threshold(self, verifier_with_validators):
        """测试消息验证阈值"""
        from wallethub.modules.cross_chain.message_verifier import CrossChainMessage
        from tests.test_factories import TestPrivateKeys

        message = CrossChainMessage(**CrossChainFactory.create_cross_chain_message())

        sig_alice = verifier_with_validators.sign_message(message, TestPrivateKeys.ALICE)
        verifier_with_validators.add_signature(message, sig_alice, TestAddresses.ALICE)

        assert not verifier_with_validators.is_verified(message)

        sig_bob = verifier_with_validators.sign_message(message, TestPrivateKeys.BOB)
        verifier_with_validators.add_signature(message, sig_bob, TestAddresses.BOB)

        assert verifier_with_validators.is_verified(message)

    def test_verify_merkle_proof(self, verifier):
        """测试Merkle证明验证"""
        leaf = "0x" + "a" * 64
        proof = ["0x" + "b" * 64, "0x" + "c" * 64]
        root = "0x" + "d" * 64

        with patch.object(verifier, 'verify_merkle_proof', return_value=True) as mock_verify:
            is_valid = verifier.verify_merkle_proof(leaf, proof, root)
            assert is_valid is True
            mock_verify.assert_called_once_with(leaf, proof, root)

    def test_verify_and_store_message(self, verifier_with_validators):
        """测试验证并存储消息"""
        from wallethub.modules.cross_chain.message_verifier import CrossChainMessage
        from tests.test_factories import TestPrivateKeys

        message = CrossChainMessage(**CrossChainFactory.create_cross_chain_message())

        sig_alice = verifier_with_validators.sign_message(message, TestPrivateKeys.ALICE)
        verifier_with_validators.add_signature(message, sig_alice, TestAddresses.ALICE)

        sig_bob = verifier_with_validators.sign_message(message, TestPrivateKeys.BOB)
        verifier_with_validators.add_signature(message, sig_bob, TestAddresses.BOB)

        stored = verifier_with_validators.verify_and_store(message)
        assert stored is True

        retrieved = verifier_with_validators.get_verified_message(message.message_id)
        assert retrieved is not None
        assert retrieved.message_id == message.message_id


class TestAtomicSwapManager:
    """原子交换管理器测试"""

    @pytest.fixture
    def swap_manager(self):
        """创建原子交换管理器实例"""
        return AtomicSwapManager(default_timelock=3600)

    def test_generate_secret(self):
        """测试生成密钥和密钥哈希"""
        secret, secret_hash = AtomicSwapManager.generate_secret()

        assert secret is not None
        assert secret_hash is not None
        assert secret_hash.startswith("0x")
        assert AtomicSwapManager.verify_secret(secret, secret_hash) is True
        assert AtomicSwapManager.verify_secret("wrong_secret", secret_hash) is False

    def test_initiate_swap_success(self, swap_manager):
        """测试发起原子交换"""
        swap_data = CrossChainFactory.create_atomic_swap()
        swap = swap_manager.initiate_swap(**swap_data)

        assert swap.swap_id is not None
        assert swap.status == "initiated"
        assert swap.secret_hash is not None
        assert swap.secret is not None
        assert swap.timelock == swap_data["timelock"]

    def test_initiate_swap_negative_amount(self, swap_manager):
        """测试负金额交换异常"""
        swap_data = CrossChainFactory.create_atomic_swap()
        swap_data["source_amount"] = -100

        with pytest.raises(CrossChainError, match="Amounts must be greater than 0"):
            swap_manager.initiate_swap(**swap_data)

    def test_initiate_swap_zero_amount(self, swap_manager):
        """测试零金额交换异常"""
        swap_data = CrossChainFactory.create_atomic_swap()
        swap_data["target_amount"] = 0

        with pytest.raises(CrossChainError, match="Amounts must be greater than 0"):
            swap_manager.initiate_swap(**swap_data)

    def test_lock_source_success(self, swap_manager):
        """测试锁定源链资产"""
        swap_data = CrossChainFactory.create_atomic_swap()
        swap = swap_manager.initiate_swap(**swap_data)

        tx_hash = TestDataGenerator.random_tx_hash()
        locked_swap = swap_manager.lock_source(swap.swap_id, tx_hash)

        assert locked_swap.status == "locked"
        assert locked_swap.source_tx_hash == tx_hash

    def test_lock_source_invalid_status(self, swap_manager):
        """测试错误状态下锁定源链资产"""
        swap_data = CrossChainFactory.create_atomic_swap()
        swap = swap_manager.initiate_swap(**swap_data)
        swap_manager.lock_source(swap.swap_id, TestDataGenerator.random_tx_hash())

        with pytest.raises(CrossChainError, match="Cannot lock swap in status"):
            swap_manager.lock_source(swap.swap_id, TestDataGenerator.random_tx_hash())

    def test_redeem_with_correct_secret(self, swap_manager):
        """测试使用正确密钥赎回"""
        swap_data = CrossChainFactory.create_atomic_swap()
        swap = swap_manager.initiate_swap(**swap_data)
        swap_manager.lock_source(swap.swap_id, TestDataGenerator.random_tx_hash())

        secret = swap.secret
        redeemed = swap_manager.redeem(swap.swap_id, secret)

        assert redeemed.status == "redeemed"
        assert redeemed.secret == secret

    def test_redeem_with_wrong_secret(self, swap_manager):
        """测试使用错误密钥赎回"""
        swap_data = CrossChainFactory.create_atomic_swap()
        swap = swap_manager.initiate_swap(**swap_data)
        swap_manager.lock_source(swap.swap_id, TestDataGenerator.random_tx_hash())

        with pytest.raises(CrossChainError, match="Invalid secret"):
            swap_manager.redeem(swap.swap_id, "wrong_secret")

    def test_redeem_in_wrong_status(self, swap_manager):
        """测试错误状态下赎回"""
        swap_data = CrossChainFactory.create_atomic_swap()
        swap = swap_manager.initiate_swap(**swap_data)

        with pytest.raises(CrossChainError, match="Cannot redeem swap in status"):
            swap_manager.redeem(swap.swap_id, swap.secret)

    def test_refund_after_expiry(self, swap_manager):
        """测试过期后退款"""
        swap_data = CrossChainFactory.create_atomic_swap()
        swap_data["timelock"] = 1
        swap = swap_manager.initiate_swap(**swap_data)
        swap_manager.lock_source(swap.swap_id, TestDataGenerator.random_tx_hash())

        import time
        time.sleep(1.1)

        refunded = swap_manager.refund(swap.swap_id)
        assert refunded.status == "refunded"

    def test_refund_before_expiry(self, swap_manager):
        """测试过期前退款失败"""
        swap_data = CrossChainFactory.create_atomic_swap()
        swap_data["timelock"] = 3600
        swap = swap_manager.initiate_swap(**swap_data)
        swap_manager.lock_source(swap.swap_id, TestDataGenerator.random_tx_hash())

        with pytest.raises(CrossChainError, match="Swap has not expired yet"):
            swap_manager.refund(swap.swap_id)

    def test_swap_is_expired(self, swap_manager):
        """测试交换过期检测"""
        swap_data = CrossChainFactory.create_atomic_swap()
        swap_data["timelock"] = 1
        swap = swap_manager.initiate_swap(**swap_data)

        assert swap.is_expired() is False

        import time
        time.sleep(1.1)

        assert swap.is_expired() is True

    def test_list_swaps_filtering(self, swap_manager):
        """测试交换列表过滤"""
        from wallethub.modules.cross_chain.atomic_swap import SwapStatus

        for i in range(6):
            swap_data = CrossChainFactory.create_atomic_swap()
            swap = swap_manager.initiate_swap(**swap_data)
            if i % 2 == 0:
                swap_manager.lock_source(swap.swap_id, TestDataGenerator.random_tx_hash())

        initiated_swaps = swap_manager.list_swaps(status=SwapStatus.INITIATED)
        locked_swaps = swap_manager.list_swaps(status=SwapStatus.LOCKED)

        assert len(initiated_swaps) == 3
        assert len(locked_swaps) == 3

    def test_cleanup_expired_swaps(self, swap_manager):
        """测试清理过期交换"""
        swap_data = CrossChainFactory.create_atomic_swap()
        swap_data["timelock"] = 1
        for _ in range(3):
            swap = swap_manager.initiate_swap(**swap_data)
            swap_manager.lock_source(swap.swap_id, TestDataGenerator.random_tx_hash())

        import time
        time.sleep(1.1)

        cleaned_count = swap_manager.cleanup_expired()
        assert cleaned_count == 3

        expired_swaps = swap_manager.list_swaps(status="expired")
        assert len(expired_swaps) == 3

    def test_get_nonexistent_swap(self, swap_manager):
        """测试获取不存在的交换"""
        swap = swap_manager.get_swap("nonexistent_id")
        assert swap is None

        with pytest.raises(CrossChainError, match="Swap .* not found"):
            swap_manager._get_swap("nonexistent_id")

    @pytest.mark.asyncio
    async def test_concurrent_swap_operations(self, swap_manager):
        """测试并发交换操作的安全性"""
        swaps_data = [CrossChainFactory.create_atomic_swap() for _ in range(10)]
        swaps = [swap_manager.initiate_swap(**data) for data in swaps_data]

        async def operate_swap(swap):
            try:
                swap_manager.lock_source(swap.swap_id, TestDataGenerator.random_tx_hash())
                return swap_manager.redeem(swap.swap_id, swap.secret)
            except CrossChainError:
                return None

        tasks = [operate_swap(swap) for swap in swaps]
        results = await asyncio.gather(*tasks)

        success_count = sum(1 for r in results if r is not None and r.status == "redeemed")
        assert success_count == 10
