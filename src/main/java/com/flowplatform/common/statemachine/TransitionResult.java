package com.flowplatform.common.statemachine;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TransitionResult {
    private boolean success;
    private String errorMessage;
    private FlowState newFlowState;
    private NodeState newNodeState;
    private LocalDateTime timestamp;

    public static TransitionResult ok(FlowState newFlowState, NodeState newNodeState) {
        TransitionResult r = new TransitionResult();
        r.setSuccess(true);
        r.setNewFlowState(newFlowState);
        r.setNewNodeState(newNodeState);
        r.setTimestamp(LocalDateTime.now());
        return r;
    }

    public static TransitionResult fail(String message) {
        TransitionResult r = new TransitionResult();
        r.setSuccess(false);
        r.setErrorMessage(message);
        return r;
    }
}
