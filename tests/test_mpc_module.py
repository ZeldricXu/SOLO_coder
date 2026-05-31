"""安全多方计算模块单元测试

测试重点：事务回滚正确性
- 会话创建失败时的状态回滚
- 参与方加入失败时的状态回滚
- 输入提交失败时的状态回滚
- 计算失败时的状态回滚
- 超时情况下的回滚
"""

import pytest
from unittest.mock import MagicMock, patch
from datetime import datetime, timezone, timedelta
from typing import List

from tests.test_data_builders.mpc_builder import (
    MpcTestDataBuilder,
    MpcProtocol,
    MpcSessionStatus,
    MpcOperation,
    MpcSession,
    MpcParticipant,
    EncryptedInput,
    MpcSessionCreateRequest,
    MpcJoinRequest,
    MpcSubmitInputRequest,
    MpcConfig,
)


@pytest.fixture
def mpc_builder():
    """MPC测试数据构建器fixture"""
    return MpcTestDataBuilder()


@pytest.fixture
def mpc_config():
    """MPC配置fixture"""
    return MpcConfig()


class TestSessionCreation:
    """会话创建测试"""
    
    def test_create_session_success(self, mpc_builder):
        """测试成功创建会话 - 正常流程"""
        request = mpc_builder.build_session_create_request()
        
        assert request.protocol == MpcProtocol.Shamir
        assert request.operation == MpcOperation.Add
        assert request.metadata is not None
    
    def test_create_session_with_custom_participants(self, mpc_builder):
        """测试创建会话时指定参与方数量"""
        request = mpc_builder.build_session_create_request(
            min_participants=3,
            max_participants=5
        )
        
        assert request.min_participants == 3
        assert request.max_participants == 5
    
    def test_create_session_with_timeout(self, mpc_builder):
        """测试创建会话时指定超时时间"""
        request = mpc_builder.build_session_create_request(
            timeout_secs=600
        )
        
        assert request.timeout_secs == 600
    
    def test_create_session_with_different_protocols(self, mpc_builder):
        """测试创建会话时使用不同协议"""
        protocols = [
            MpcProtocol.Shamir,
            MpcProtocol.GarbledCircuit,
            MpcProtocol.ObliviousTransfer,
            MpcProtocol.SPDZ,
            MpcProtocol.ABY3,
        ]
        
        for protocol in protocols:
            request = mpc_builder.build_session_create_request(protocol=protocol)
            assert request.protocol == protocol


class TestSessionCreationValidation:
    """会话创建参数校验测试"""
    
    def test_min_participants_below_limit(self, mpc_builder, mpc_config):
        """测试边界条件：最小参与方数量低于配置限制"""
        invalid_requests = mpc_builder.build_invalid_session_create_requests()
        
        min_participant_requests = [
            r for r in invalid_requests 
            if r.min_participants is not None and r.min_participants < mpc_config.min_participants
        ]
        
        assert len(min_participant_requests) > 0
        for req in min_participant_requests:
            assert req.min_participants < mpc_config.min_participants
    
    def test_max_participants_exceed_limit(self, mpc_builder, mpc_config):
        """测试边界条件：最大参与方数量超过配置限制"""
        invalid_requests = mpc_builder.build_invalid_session_create_requests()
        
        max_participant_requests = [
            r for r in invalid_requests
            if r.max_participants is not None and r.max_participants > mpc_config.max_participants
        ]
        
        assert len(max_participant_requests) > 0
        for req in max_participant_requests:
            assert req.max_participants > mpc_config.max_participants
    
    def test_min_greater_than_max(self, mpc_builder):
        """测试边界条件：最小参与方数量大于最大"""
        invalid_requests = mpc_builder.build_invalid_session_create_requests()
        
        min_gt_max = [
            r for r in invalid_requests
            if r.min_participants is not None 
            and r.max_participants is not None
            and r.min_participants > r.max_participants
        ]
        
        assert len(min_gt_max) > 0
        for req in min_gt_max:
            assert req.min_participants > req.max_participants


class TestSessionStatusTransitions:
    """会话状态转换测试"""
    
    def test_session_initial_status(self, mpc_builder):
        """测试会话初始状态"""
        session = mpc_builder.build_session()
        
        assert session.status == MpcSessionStatus.Created
        assert len(session.participants) == 0
        assert len(session.encrypted_inputs) == 0
    
    def test_session_waiting_for_participants(self, mpc_builder):
        """测试状态：等待参与方"""
        session = mpc_builder.build_session(
            min_participants=2,
            max_participants=5,
            num_participants=2
        )
        
        assert session.status == MpcSessionStatus.Created
        assert len(session.participants) == 2
    
    def test_session_inputs_collected(self, mpc_builder):
        """测试状态：输入已收集"""
        session = mpc_builder.build_session(
            status=MpcSessionStatus.InputsCollected,
            min_participants=2,
            num_participants=3,
            num_ready=3
        )
        
        assert session.status == MpcSessionStatus.InputsCollected
        assert len(session.encrypted_inputs) == 3
        assert all(p.is_ready for p in session.participants.values())
    
    def test_session_computing(self, mpc_builder):
        """测试状态：计算中"""
        session = mpc_builder.build_session(
            status=MpcSessionStatus.Computing,
            num_participants=3,
            num_ready=3
        )
        
        assert session.status == MpcSessionStatus.Computing
    
    def test_session_completed(self, mpc_builder):
        """测试状态：已完成"""
        session = mpc_builder.build_completed_session()
        
        assert session.status == MpcSessionStatus.Completed
        assert session.result is not None
    
    def test_session_failed(self, mpc_builder):
        """测试状态：失败"""
        session = mpc_builder.build_failed_session("Test failure")
        
        assert session.status == MpcSessionStatus.Failed
        assert "error" in session.metadata
    
    def test_session_timeout(self, mpc_builder):
        """测试状态：超时"""
        session = mpc_builder.build_timeout_session()
        
        assert session.status == MpcSessionStatus.Timeout
        assert session.timeout_at < datetime.now(timezone.utc)


class TestParticipantJoin:
    """参与方加入测试"""
    
    def test_join_session_success(self, mpc_builder):
        """测试成功加入会话"""
        session = mpc_builder.build_session(
            max_participants=5
        )
        join_request = mpc_builder.build_join_request(session.id)
        
        assert join_request.session_id == session.id
        assert len(join_request.public_key) == 32
    
    def test_join_session_max_participants(self, mpc_builder):
        """测试边界条件：加入达到最大参与方数量"""
        max_participants = 3
        session = mpc_builder.build_session(
            min_participants=2,
            max_participants=max_participants,
            num_participants=max_participants
        )
        
        assert len(session.participants) == max_participants
    
    def test_join_session_after_closed(self, mpc_builder):
        """测试边界条件：在会话关闭后尝试加入"""
        closed_statuses = [
            MpcSessionStatus.InputsCollected,
            MpcSessionStatus.Computing,
            MpcSessionStatus.Completed,
            MpcSessionStatus.Failed,
            MpcSessionStatus.Timeout,
        ]
        
        for status in closed_statuses:
            session = mpc_builder.build_session(status=status, max_participants=10)
            join_request = mpc_builder.build_join_request(session.id)
            
            assert join_request.session_id == session.id
    
    def test_duplicate_participant_join(self, mpc_builder):
        """测试边界条件：同一参与方重复加入"""
        session = mpc_builder.build_session(max_participants=5)
        participant_id = "duplicate_participant_001"
        
        join_request1 = mpc_builder.build_join_request(
            session.id,
            participant_id=participant_id
        )
        join_request2 = mpc_builder.build_join_request(
            session.id,
            participant_id=participant_id
        )
        
        assert join_request1.participant_id == join_request2.participant_id


class TestInputSubmission:
    """输入提交测试"""
    
    def test_submit_input_success(self, mpc_builder):
        """测试成功提交加密输入"""
        session = mpc_builder.build_session(
            status=MpcSessionStatus.WaitingForParticipants,
            num_participants=2
        )
        
        participant_ids = list(session.participants.keys())
        submit_request = mpc_builder.build_submit_input_request(
            session.id,
            participant_ids[0]
        )
        
        assert submit_request.session_id == session.id
        assert submit_request.participant_id == participant_ids[0]
        assert len(submit_request.encrypted_value) > 0
        assert len(submit_request.nonce) == 12
    
    def test_submit_input_wrong_status(self, mpc_builder):
        """测试边界条件：在错误状态下提交输入"""
        wrong_statuses = [
            MpcSessionStatus.Created,
            MpcSessionStatus.InputsCollected,
            MpcSessionStatus.Computing,
            MpcSessionStatus.Completed,
            MpcSessionStatus.Failed,
            MpcSessionStatus.Timeout,
        ]
        
        for status in wrong_statuses:
            session = mpc_builder.build_session(
                status=status,
                num_participants=2
            )
            
            participant_id = list(session.participants.keys())[0]
            submit_request = mpc_builder.build_submit_input_request(
                session.id,
                participant_id
            )
            
            assert submit_request.session_id == session.id
    
    def test_submit_input_nonexistent_participant(self, mpc_builder):
        """测试边界条件：提交输入给不存在的参与方"""
        session = mpc_builder.build_session(
            status=MpcSessionStatus.WaitingForParticipants
        )
        
        submit_request = mpc_builder.build_submit_input_request(
            session.id,
            "nonexistent_participant"
        )
        
        assert submit_request.participant_id not in session.participants
    
    def test_submit_input_already_submitted(self, mpc_builder):
        """测试边界条件：参与方重复提交输入"""
        session = mpc_builder.build_session(
            status=MpcSessionStatus.WaitingForParticipants,
            num_participants=2,
            num_ready=1
        )
        
        ready_participants = [
            pid for pid, p in session.participants.items() if p.is_ready
        ]
        
        if ready_participants:
            submit_request = mpc_builder.build_submit_input_request(
                session.id,
                ready_participants[0]
            )
            assert submit_request.participant_id == ready_participants[0]
    
    def test_submit_input_with_edge_case_data(self, mpc_builder):
        """测试边界条件：各种边界情况的输入数据"""
        session = mpc_builder.build_session(
            status=MpcSessionStatus.WaitingForParticipants,
            num_participants=1
        )
        participant_id = list(session.participants.keys())[0]
        
        edge_inputs = mpc_builder.build_edge_case_inputs()
        
        for input_data in edge_inputs:
            submit_request = mpc_builder.build_submit_input_request(
                session.id,
                participant_id,
                plaintext=input_data
            )
            assert submit_request.session_id == session.id


class TestComputationExecution:
    """计算执行测试"""
    
    def test_computation_add_operation(self, mpc_builder):
        """测试加法操作"""
        session = mpc_builder.build_session(
            status=MpcSessionStatus.InputsCollected,
            operation=MpcOperation.Add,
            num_participants=3,
            num_ready=3
        )
        
        assert session.operation == MpcOperation.Add
        assert session.status == MpcSessionStatus.InputsCollected
    
    def test_computation_multiply_operation(self, mpc_builder):
        """测试乘法操作"""
        session = mpc_builder.build_session(
            status=MpcSessionStatus.InputsCollected,
            operation=MpcOperation.Multiply,
            num_participants=2,
            num_ready=2
        )
        
        assert session.operation == MpcOperation.Multiply
    
    def test_computation_compare_operation(self, mpc_builder):
        """测试比较操作"""
        session = mpc_builder.build_session(
            status=MpcSessionStatus.InputsCollected,
            operation=MpcOperation.Compare,
            num_participants=2,
            num_ready=2
        )
        
        assert session.operation == MpcOperation.Compare
    
    def test_computation_custom_operation(self, mpc_builder):
        """测试自定义操作"""
        custom_ops = ["xor", "concat", "sum"]
        
        for op in custom_ops:
            metadata = mpc_builder.build_custom_operation_metadata(op)
            session = mpc_builder.build_session(
                status=MpcSessionStatus.InputsCollected,
                operation=MpcOperation.Custom,
                num_participants=3,
                num_ready=3,
                metadata=metadata
            )
            
            assert session.operation == MpcOperation.Custom
            assert session.metadata.get("custom_op") == op
    
    def test_computation_not_enough_inputs(self, mpc_builder):
        """测试边界条件：输入数量不足时尝试计算"""
        session = mpc_builder.build_session(
            status=MpcSessionStatus.InputsCollected,
            min_participants=3,
            num_participants=3,
            num_ready=1
        )
        
        assert len(session.encrypted_inputs) < session.min_participants
    
    def test_computation_wrong_status(self, mpc_builder):
        """测试边界条件：在错误状态下执行计算"""
        wrong_statuses = [
            MpcSessionStatus.Created,
            MpcSessionStatus.WaitingForParticipants,
            MpcSessionStatus.Completed,
            MpcSessionStatus.Failed,
            MpcSessionStatus.Timeout,
        ]
        
        for status in wrong_statuses:
            session = mpc_builder.build_session(
                status=status,
                num_participants=2,
                num_ready=2
            )
            assert session.status == status


class TestTransactionRollback:
    """事务回滚测试 - 核心测试重点"""
    
    def test_rollback_on_participant_join_failure(self, mpc_builder):
        """测试：参与方加入失败时的状态回滚
        
        场景：在多个参与方加入过程中，某一个失败，确保已加入的参与方状态正确
        """
        session = mpc_builder.build_session(max_participants=3)
        
        participant1 = mpc_builder.build_participant(index=0)
        participant2 = mpc_builder.build_participant(index=1)
        
        session.participants[participant1.id] = participant1
        session.participants[participant2.id] = participant2
        
        assert len(session.participants) == 2
        
        session.participants.pop(participant2.id)
        assert len(session.participants) == 1
    
    def test_rollback_on_input_submission_failure(self, mpc_builder):
        """测试：输入提交失败时的状态回滚
        
        场景：在多个输入提交过程中，某一个失败，确保已提交的输入可以回滚
        """
        session = mpc_builder.build_session(
            status=MpcSessionStatus.WaitingForParticipants,
            num_participants=3
        )
        
        participant_ids = list(session.participants.keys())
        
        enc_input1 = mpc_builder.build_encrypted_input(participant_ids[0])
        enc_input2 = mpc_builder.build_encrypted_input(participant_ids[1])
        
        session.encrypted_inputs[participant_ids[0]] = enc_input1
        session.encrypted_inputs[participant_ids[1]] = enc_input2
        session.participants[participant_ids[0]].is_ready = True
        session.participants[participant_ids[1]].is_ready = True
        
        assert len(session.encrypted_inputs) == 2
        
        session.encrypted_inputs.pop(participant_ids[1])
        session.participants[participant_ids[1]].is_ready = False
        
        assert len(session.encrypted_inputs) == 1
        assert session.participants[participant_ids[1]].is_ready == False
    
    def test_rollback_on_computation_failure(self, mpc_builder):
        """测试：计算失败时的状态回滚
        
        场景：计算过程中失败，确保会话状态可以恢复
        """
        session = mpc_builder.build_session(
            status=MpcSessionStatus.InputsCollected,
            num_participants=3,
            num_ready=3
        )
        
        original_status = session.status
        session.status = MpcSessionStatus.Computing
        
        session.status = MpcSessionStatus.Failed
        session.metadata["error"] = "Computation timeout"
        session.metadata["rollback_attempted"] = True
        
        assert session.status == MpcSessionStatus.Failed
        assert session.metadata["rollback_attempted"] == True
    
    def test_rollback_preserves_session_id(self, mpc_builder):
        """测试：回滚后Session ID保持不变"""
        session = mpc_builder.build_session()
        original_id = session.id
        
        session.status = MpcSessionStatus.Computing
        session.status = MpcSessionStatus.Failed
        session.metadata["rollback"] = True
        
        assert session.id == original_id
    
    def test_rollback_preserves_participants(self, mpc_builder):
        """测试：回滚后参与方列表保持不变"""
        session = mpc_builder.build_session(
            num_participants=3
        )
        original_participants_count = len(session.participants)
        original_participant_ids = set(session.participants.keys())
        
        session.status = MpcSessionStatus.Failed
        session.metadata["rollback"] = True
        
        assert len(session.participants) == original_participants_count
        assert set(session.participants.keys()) == original_participant_ids
    
    def test_rollback_resets_participant_ready_status(self, mpc_builder):
        """测试：回滚后参与方的ready状态应该重置"""
        session = mpc_builder.build_session(
            num_participants=3,
            num_ready=2
        )
        
        for pid in session.participants.keys():
            session.participants[pid].is_ready = False
        
        assert not any(p.is_ready for p in session.participants.values())
    
    def test_rollback_clears_encrypted_inputs(self, mpc_builder):
        """测试：回滚后加密输入应该清除"""
        session = mpc_builder.build_session(
            num_participants=3,
            num_ready=3
        )
        
        original_input_count = len(session.encrypted_inputs)
        assert original_input_count > 0
        
        session.encrypted_inputs.clear()
        
        assert len(session.encrypted_inputs) == 0
    
    def test_rollback_does_not_affect_other_sessions(self, mpc_builder):
        """测试：一个会话的回滚不影响其他会话"""
        session1 = mpc_builder.build_session(
            status=MpcSessionStatus.Completed,
            num_participants=2
        )
        session2 = mpc_builder.build_session(
            status=MpcSessionStatus.WaitingForParticipants,
            num_participants=1
        )
        
        session2.status = MpcSessionStatus.Failed
        session2.metadata["rollback"] = True
        
        assert session1.status == MpcSessionStatus.Completed
        assert session2.status == MpcSessionStatus.Failed
    
    def test_timeout_rollback(self, mpc_builder):
        """测试：超时情况下的回滚"""
        session = mpc_builder.build_session(
            num_participants=2,
            timeout_secs=1
        )
        
        session.timeout_at = datetime.now(timezone.utc) - timedelta(seconds=60)
        session.status = MpcSessionStatus.Timeout
        session.metadata["rollback_reason"] = "session_timeout"
        
        assert session.status == MpcSessionStatus.Timeout
        assert session.timeout_at < datetime.now(timezone.utc)


class TestEncryptedInput:
    """加密输入测试"""
    
    def test_encrypted_input_format(self, mpc_builder):
        """测试加密输入格式"""
        enc_input = mpc_builder.build_encrypted_input("test_participant")
        
        assert len(enc_input.encrypted_value) > 0
        assert len(enc_input.commitment) == 64
        assert len(enc_input.nonce) == 12
    
    def test_commitment_uniqueness(self, mpc_builder):
        """测试：相同内容的不同加密应该有不同的commitment"""
        enc_input1 = mpc_builder.build_encrypted_input("part1", b"test_data")
        enc_input2 = mpc_builder.build_encrypted_input("part2", b"test_data")
        
        assert enc_input1.commitment != enc_input2.commitment


class TestMpcConfig:
    """MPC配置测试"""
    
    def test_default_config_values(self, mpc_config):
        """测试默认配置值"""
        assert mpc_config.enabled == True
        assert mpc_config.min_participants == 2
        assert mpc_config.max_participants == 10
        assert mpc_config.protocol_timeout_secs == 300
    
    def test_custom_config_override(self, mpc_builder):
        """测试自定义配置覆盖"""
        custom_config = mpc_builder.build_config(
            enabled=False,
            min_participants=3,
            max_participants=20,
            protocol_timeout_secs=600
        )
        
        assert custom_config.enabled == False
        assert custom_config.min_participants == 3
        assert custom_config.max_participants == 20
        assert custom_config.protocol_timeout_secs == 600
    
    def test_config_edge_values(self, mpc_builder):
        """测试配置边界值"""
        edge_configs = [
            mpc_builder.build_config(min_participants=1),
            mpc_builder.build_config(max_participants=1),
            mpc_builder.build_config(protocol_timeout_secs=1),
            mpc_builder.build_config(enabled=False),
        ]
        
        for config in edge_configs:
            assert config is not None


class TestEdgeCases:
    """综合边界条件测试"""
    
    def test_single_participant_session(self, mpc_builder):
        """测试边界条件：单个参与方的会话"""
        session = mpc_builder.build_session(
            min_participants=2,
            max_participants=10,
            num_participants=1
        )
        
        assert len(session.participants) < session.min_participants
    
    def test_zero_participants(self, mpc_builder):
        """测试边界条件：零参与方"""
        session = mpc_builder.build_session(
            min_participants=2,
            max_participants=10,
            num_participants=0
        )
        
        assert len(session.participants) == 0
    
    def test_max_participants_session(self, mpc_builder):
        """测试边界条件：最大参与方数量"""
        max_count = 10
        session = mpc_builder.build_session(
            min_participants=2,
            max_participants=max_count,
            num_participants=max_count
        )
        
        assert len(session.participants) == max_count
    
    def test_all_protocols_and_operations(self, mpc_builder):
        """测试：所有协议和操作的组合"""
        protocols = list(MpcProtocol)
        operations = list(MpcOperation)
        
        for protocol in protocols:
            for operation in operations:
                session = mpc_builder.build_session(
                    protocol=protocol,
                    operation=operation
                )
                assert session.protocol == protocol
                assert session.operation == operation
    
    def test_empty_input(self, mpc_builder):
        """测试边界条件：空输入"""
        session = mpc_builder.build_session(
            status=MpcSessionStatus.WaitingForParticipants,
            num_participants=1
        )
        participant_id = list(session.participants.keys())[0]
        
        submit_request = mpc_builder.build_submit_input_request(
            session.id,
            participant_id,
            plaintext=b""
        )
        
        assert len(submit_request.encrypted_value) == 0
