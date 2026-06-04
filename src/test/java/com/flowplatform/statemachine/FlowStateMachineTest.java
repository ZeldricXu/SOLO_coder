package com.flowplatform.statemachine;

import com.flowplatform.common.statemachine.FlowState;
import com.flowplatform.common.statemachine.FlowStateMachine;
import com.flowplatform.common.statemachine.NodeState;
import com.flowplatform.common.statemachine.TransitionResult;
import com.flowplatform.test.BaseUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("流程状态机测试")
public class FlowStateMachineTest extends BaseUnitTest {

    private FlowStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new FlowStateMachine();
    }

    @Nested
    @DisplayName("流程状态转移测试")
    class FlowStateTransitionTests {

        @Test
        @DisplayName("DRAFT → PENDING 转移成功")
        public void testDraftToPending() {
            TransitionResult result = stateMachine.transition(FlowState.DRAFT, FlowState.PENDING);
            assertTrue(result.isSuccess());
            assertEquals(FlowState.PENDING, result.getNewFlowState());
        }

        @Test
        @DisplayName("DRAFT → REVOKED 转移成功")
        public void testDraftToRevoked() {
            TransitionResult result = stateMachine.transition(FlowState.DRAFT, FlowState.REVOKED);
            assertTrue(result.isSuccess());
            assertEquals(FlowState.REVOKED, result.getNewFlowState());
        }

        @Test
        @DisplayName("PENDING → APPROVED 转移成功")
        public void testPendingToApproved() {
            TransitionResult result = stateMachine.transition(FlowState.PENDING, FlowState.APPROVED);
            assertTrue(result.isSuccess());
            assertEquals(FlowState.APPROVED, result.getNewFlowState());
        }

        @Test
        @DisplayName("PENDING → REJECTED 转移成功")
        public void testPendingToRejected() {
            TransitionResult result = stateMachine.transition(FlowState.PENDING, FlowState.REJECTED);
            assertTrue(result.isSuccess());
            assertEquals(FlowState.REJECTED, result.getNewFlowState());
        }

        @Test
        @DisplayName("PENDING → RETURNED 转移成功")
        public void testPendingToReturned() {
            TransitionResult result = stateMachine.transition(FlowState.PENDING, FlowState.RETURNED);
            assertTrue(result.isSuccess());
            assertEquals(FlowState.RETURNED, result.getNewFlowState());
        }

        @Test
        @DisplayName("PENDING → REVOKED 转移成功")
        public void testPendingToRevoked() {
            TransitionResult result = stateMachine.transition(FlowState.PENDING, FlowState.REVOKED);
            assertTrue(result.isSuccess());
            assertEquals(FlowState.REVOKED, result.getNewFlowState());
        }

        @Test
        @DisplayName("RETURNED → PENDING 转移成功")
        public void testReturnedToPending() {
            TransitionResult result = stateMachine.transition(FlowState.RETURNED, FlowState.PENDING);
            assertTrue(result.isSuccess());
            assertEquals(FlowState.PENDING, result.getNewFlowState());
        }

        @Test
        @DisplayName("RETURNED → REVOKED 转移成功")
        public void testReturnedToRevoked() {
            TransitionResult result = stateMachine.transition(FlowState.RETURNED, FlowState.REVOKED);
            assertTrue(result.isSuccess());
            assertEquals(FlowState.REVOKED, result.getNewFlowState());
        }

        @Test
        @DisplayName("APPROVED → COMPLETED 转移成功")
        public void testApprovedToCompleted() {
            TransitionResult result = stateMachine.transition(FlowState.APPROVED, FlowState.COMPLETED);
            assertTrue(result.isSuccess());
            assertEquals(FlowState.COMPLETED, result.getNewFlowState());
        }

        @Test
        @DisplayName("APPROVED → REVOKED 转移成功")
        public void testApprovedToRevoked() {
            TransitionResult result = stateMachine.transition(FlowState.APPROVED, FlowState.REVOKED);
            assertTrue(result.isSuccess());
            assertEquals(FlowState.REVOKED, result.getNewFlowState());
        }

        @Test
        @DisplayName("REJECTED → DRAFT 转移成功")
        public void testRejectedToDraft() {
            TransitionResult result = stateMachine.transition(FlowState.REJECTED, FlowState.DRAFT);
            assertTrue(result.isSuccess());
            assertEquals(FlowState.DRAFT, result.getNewFlowState());
        }
    }

    @Nested
    @DisplayName("非法流程状态转移测试")
    class InvalidFlowStateTransitionTests {

        @Test
        @DisplayName("APPROVED → PENDING 转移失败")
        public void testApprovedToPendingFail() {
            TransitionResult result = stateMachine.transition(FlowState.APPROVED, FlowState.PENDING);
            assertFalse(result.isSuccess());
            assertNotNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("COMPLETED → PENDING 转移失败")
        public void testCompletedToPendingFail() {
            TransitionResult result = stateMachine.transition(FlowState.COMPLETED, FlowState.PENDING);
            assertFalse(result.isSuccess());
            assertNotNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("REVOKED → PENDING 转移失败")
        public void testRevokedToPendingFail() {
            TransitionResult result = stateMachine.transition(FlowState.REVOKED, FlowState.PENDING);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("COMPLETED → DRAFT 转移失败")
        public void testCompletedToDraftFail() {
            TransitionResult result = stateMachine.transition(FlowState.COMPLETED, FlowState.DRAFT);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("DRAFT → APPROVED 转移失败")
        public void testDraftToApprovedFail() {
            TransitionResult result = stateMachine.transition(FlowState.DRAFT, FlowState.APPROVED);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("DRAFT → COMPLETED 转移失败")
        public void testDraftToCompletedFail() {
            TransitionResult result = stateMachine.transition(FlowState.DRAFT, FlowState.COMPLETED);
            assertFalse(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("节点状态转移测试")
    class NodeStateTransitionTests {

        @Test
        @DisplayName("PENDING → APPROVED 节点转移成功")
        public void testNodePendingToApproved() {
            TransitionResult result = stateMachine.transitionNode(NodeState.PENDING, NodeState.APPROVED);
            assertTrue(result.isSuccess());
            assertEquals(NodeState.APPROVED, result.getNewNodeState());
        }

        @Test
        @DisplayName("PENDING → REJECTED 节点转移成功")
        public void testNodePendingToRejected() {
            TransitionResult result = stateMachine.transitionNode(NodeState.PENDING, NodeState.REJECTED);
            assertTrue(result.isSuccess());
            assertEquals(NodeState.REJECTED, result.getNewNodeState());
        }

        @Test
        @DisplayName("PENDING → RETURNED 节点转移成功")
        public void testNodePendingToReturned() {
            TransitionResult result = stateMachine.transitionNode(NodeState.PENDING, NodeState.RETURNED);
            assertTrue(result.isSuccess());
            assertEquals(NodeState.RETURNED, result.getNewNodeState());
        }

        @Test
        @DisplayName("PENDING → SKIPPED 节点转移成功")
        public void testNodePendingToSkipped() {
            TransitionResult result = stateMachine.transitionNode(NodeState.PENDING, NodeState.SKIPPED);
            assertTrue(result.isSuccess());
            assertEquals(NodeState.SKIPPED, result.getNewNodeState());
        }

        @Test
        @DisplayName("PENDING → TRANSFERRED 节点转移成功")
        public void testNodePendingToTransferred() {
            TransitionResult result = stateMachine.transitionNode(NodeState.PENDING, NodeState.TRANSFERRED);
            assertTrue(result.isSuccess());
            assertEquals(NodeState.TRANSFERRED, result.getNewNodeState());
        }

        @Test
        @DisplayName("TRANSFERRED → PENDING 节点转移成功")
        public void testNodeTransferredToPending() {
            TransitionResult result = stateMachine.transitionNode(NodeState.TRANSFERRED, NodeState.PENDING);
            assertTrue(result.isSuccess());
            assertEquals(NodeState.PENDING, result.getNewNodeState());
        }
    }

    @Nested
    @DisplayName("非法节点状态转移测试")
    class InvalidNodeStateTransitionTests {

        @Test
        @DisplayName("APPROVED → PENDING 节点转移失败")
        public void testNodeApprovedToPendingFail() {
            TransitionResult result = stateMachine.transitionNode(NodeState.APPROVED, NodeState.PENDING);
            assertFalse(result.isSuccess());
            assertNotNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("REJECTED → PENDING 节点转移失败")
        public void testNodeRejectedToPendingFail() {
            TransitionResult result = stateMachine.transitionNode(NodeState.REJECTED, NodeState.PENDING);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("RETURNED → APPROVED 节点转移失败")
        public void testNodeReturnedToApprovedFail() {
            TransitionResult result = stateMachine.transitionNode(NodeState.RETURNED, NodeState.APPROVED);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("SKIPPED → PENDING 节点转移失败")
        public void testNodeSkippedToPendingFail() {
            TransitionResult result = stateMachine.transitionNode(NodeState.SKIPPED, NodeState.PENDING);
            assertFalse(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("canTransition 方法测试")
    class CanTransitionTests {

        @Test
        @DisplayName("合法转移 canTransition 返回 true")
        public void testCanTransitionValid() {
            assertTrue(stateMachine.canTransition(FlowState.DRAFT, FlowState.PENDING));
            assertTrue(stateMachine.canTransition(FlowState.PENDING, FlowState.APPROVED));
            assertTrue(stateMachine.canTransition(FlowState.REJECTED, FlowState.DRAFT));
        }

        @Test
        @DisplayName("非法转移 canTransition 返回 false")
        public void testCanTransitionInvalid() {
            assertFalse(stateMachine.canTransition(FlowState.APPROVED, FlowState.PENDING));
            assertFalse(stateMachine.canTransition(FlowState.COMPLETED, FlowState.PENDING));
            assertFalse(stateMachine.canTransition(FlowState.REVOKED, FlowState.DRAFT));
        }

        @Test
        @DisplayName("canTransitionNode 合法转移返回 true")
        public void testCanTransitionNodeValid() {
            assertTrue(stateMachine.canTransitionNode(NodeState.PENDING, NodeState.APPROVED));
            assertTrue(stateMachine.canTransitionNode(NodeState.PENDING, NodeState.REJECTED));
            assertTrue(stateMachine.canTransitionNode(NodeState.TRANSFERRED, NodeState.PENDING));
        }

        @Test
        @DisplayName("canTransitionNode 非法转移返回 false")
        public void testCanTransitionNodeInvalid() {
            assertFalse(stateMachine.canTransitionNode(NodeState.APPROVED, NodeState.PENDING));
            assertFalse(stateMachine.canTransitionNode(NodeState.REJECTED, NodeState.APPROVED));
        }
    }

    @Nested
    @DisplayName("determineFlowStateFromNodes 测试")
    class DetermineFlowStateTests {

        @Test
        @DisplayName("所有节点审批通过 → APPROVED")
        public void testAllApproved() {
            FlowState state = stateMachine.determineFlowStateFromNodes(
                    List.of("APPROVE", "APPROVE", "APPROVE"));
            assertEquals(FlowState.APPROVED, state);
        }

        @Test
        @DisplayName("存在拒绝节点 → REJECTED")
        public void testAnyRejected() {
            FlowState state = stateMachine.determineFlowStateFromNodes(
                    List.of("APPROVE", "REJECTED", "APPROVE"));
            assertEquals(FlowState.REJECTED, state);
        }

        @Test
        @DisplayName("存在退回节点 → RETURNED")
        public void testAnyReturned() {
            FlowState state = stateMachine.determineFlowStateFromNodes(
                    List.of("APPROVE", "RETURNED", "APPROVE"));
            assertEquals(FlowState.RETURNED, state);
        }

        @Test
        @DisplayName("存在待审批节点 → PENDING")
        public void testMixedPending() {
            FlowState state = stateMachine.determineFlowStateFromNodes(
                    List.of("APPROVE", "PENDING"));
            assertEquals(FlowState.PENDING, state);
        }

        @Test
        @DisplayName("拒绝优先于退回")
        public void testRejectBeforeReturn() {
            FlowState state = stateMachine.determineFlowStateFromNodes(
                    List.of("REJECTED", "RETURNED"));
            assertEquals(FlowState.REJECTED, state);
        }

        @Test
        @DisplayName("退回优先于待审批")
        public void testReturnBeforePending() {
            FlowState state = stateMachine.determineFlowStateFromNodes(
                    List.of("RETURNED", "PENDING"));
            assertEquals(FlowState.RETURNED, state);
        }

        @Test
        @DisplayName("空节点列表 → APPROVED")
        public void testEmptyNodes() {
            FlowState state = stateMachine.determineFlowStateFromNodes(List.of());
            assertEquals(FlowState.APPROVED, state);
        }
    }

    @Nested
    @DisplayName("resolveFlowStateAfterNodeAction 测试")
    class ResolveFlowStateTests {

        @Test
        @DisplayName("APPROVE 动作 → PENDING")
        public void testApproveAction() {
            FlowState state = stateMachine.resolveFlowStateAfterNodeAction("APPROVE");
            assertEquals(FlowState.PENDING, state);
        }

        @Test
        @DisplayName("REJECT 动作 → REJECTED")
        public void testRejectAction() {
            FlowState state = stateMachine.resolveFlowStateAfterNodeAction("REJECT");
            assertEquals(FlowState.REJECTED, state);
        }

        @Test
        @DisplayName("RETURN 动作 → RETURNED")
        public void testReturnAction() {
            FlowState state = stateMachine.resolveFlowStateAfterNodeAction("RETURN");
            assertEquals(FlowState.RETURNED, state);
        }

        @Test
        @DisplayName("未知动作 → PENDING")
        public void testUnknownAction() {
            FlowState state = stateMachine.resolveFlowStateAfterNodeAction("UNKNOWN");
            assertEquals(FlowState.PENDING, state);
        }
    }
}
