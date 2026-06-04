package com.flowplatform.common.statemachine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class FlowStateMachine {

    private static final Map<FlowState, Set<FlowState>> VALID_TRANSITIONS = new EnumMap<>(FlowState.class);
    private static final Map<NodeState, Set<NodeState>> NODE_TRANSITIONS = new EnumMap<>(NodeState.class);

    static {
        VALID_TRANSITIONS.put(FlowState.DRAFT, EnumSet.of(FlowState.PENDING, FlowState.REVOKED));
        VALID_TRANSITIONS.put(FlowState.PENDING, EnumSet.of(FlowState.APPROVED, FlowState.REJECTED, FlowState.RETURNED, FlowState.REVOKED));
        VALID_TRANSITIONS.put(FlowState.RETURNED, EnumSet.of(FlowState.PENDING, FlowState.REVOKED));
        VALID_TRANSITIONS.put(FlowState.APPROVED, EnumSet.of(FlowState.COMPLETED, FlowState.REVOKED));
        VALID_TRANSITIONS.put(FlowState.REJECTED, EnumSet.of(FlowState.DRAFT));
        VALID_TRANSITIONS.put(FlowState.REVOKED, EnumSet.noneOf(FlowState.class));
        VALID_TRANSITIONS.put(FlowState.COMPLETED, EnumSet.noneOf(FlowState.class));

        NODE_TRANSITIONS.put(NodeState.PENDING, EnumSet.of(NodeState.APPROVED, NodeState.REJECTED, NodeState.RETURNED, NodeState.SKIPPED, NodeState.TRANSFERRED));
        NODE_TRANSITIONS.put(NodeState.TRANSFERRED, EnumSet.of(NodeState.PENDING));
        NODE_TRANSITIONS.put(NodeState.APPROVED, EnumSet.noneOf(NodeState.class));
        NODE_TRANSITIONS.put(NodeState.REJECTED, EnumSet.noneOf(NodeState.class));
        NODE_TRANSITIONS.put(NodeState.RETURNED, EnumSet.noneOf(NodeState.class));
        NODE_TRANSITIONS.put(NodeState.SKIPPED, EnumSet.noneOf(NodeState.class));
    }

    public TransitionResult transition(FlowState from, FlowState to) {
        log.info("流程状态转移尝试: {} → {}", from, to);
        Set<FlowState> allowed = VALID_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(FlowState.class));
        if (allowed.contains(to)) {
            log.info("流程状态转移成功: {} → {}", from, to);
            return TransitionResult.ok(to, null);
        }
        log.warn("非法流程状态转移: {} → {}", from, to);
        return TransitionResult.fail("非法状态转移: " + from + " → " + to);
    }

    public TransitionResult transitionNode(NodeState from, NodeState to) {
        log.info("节点状态转移尝试: {} → {}", from, to);
        Set<NodeState> allowed = NODE_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(NodeState.class));
        if (allowed.contains(to)) {
            log.info("节点状态转移成功: {} → {}", from, to);
            return TransitionResult.ok(null, to);
        }
        log.warn("非法节点状态转移: {} → {}", from, to);
        return TransitionResult.fail("非法节点状态转移: " + from + " → " + to);
    }

    public boolean canTransition(FlowState from, FlowState to) {
        return VALID_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(FlowState.class)).contains(to);
    }

    public boolean canTransitionNode(NodeState from, NodeState to) {
        return NODE_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(NodeState.class)).contains(to);
    }

    public FlowState resolveFlowStateAfterNodeAction(String action) {
        switch (action) {
            case "APPROVE":
                return FlowState.PENDING;
            case "REJECT":
                return FlowState.REJECTED;
            case "RETURN":
                return FlowState.RETURNED;
            default:
                return FlowState.PENDING;
        }
    }

    public FlowState determineFlowStateFromNodes(List<String> nodeStatuses) {
        boolean allDone = true;
        for (String status : nodeStatuses) {
            if ("REJECTED".equals(status)) {
                return FlowState.REJECTED;
            }
            if ("RETURNED".equals(status)) {
                return FlowState.RETURNED;
            }
            if ("PENDING".equals(status)) {
                allDone = false;
            }
        }
        if (allDone) {
            return FlowState.APPROVED;
        }
        return FlowState.PENDING;
    }
}
