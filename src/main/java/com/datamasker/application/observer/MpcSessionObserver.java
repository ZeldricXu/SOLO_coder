package com.datamasker.application.observer;

import com.datamasker.domain.mpc.monitor.MpcMetrics;
import com.datamasker.domain.mpc.monitor.MpcStatusExposer;
import com.datamasker.domain.mpc.model.MpcComputationResult;
import com.datamasker.domain.mpc.model.MpcSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MpcSessionObserver {

    private final MpcMetrics mpcMetrics;
    private final MpcStatusExposer statusExposer;

    public void onSessionCreated(MpcSession session) {
        mpcMetrics.incrementActive();
        statusExposer.registerSession(session);
    }

    public void onInputSubmitted(MpcSession session, String partyId) {
        int pending = session.getPartyCount() - session.getParties().size();
        mpcMetrics.updatePendingInputs(Math.max(pending, 0));
        statusExposer.updateSessionStatus(session, session.getStatus());
    }

    public void onSessionCompleted(MpcSession session, MpcComputationResult result) {
        mpcMetrics.decrementActive();
        mpcMetrics.updatePendingInputs(0);
        statusExposer.removeSession(session.getSessionId());
    }

    public void onSessionFailed(MpcSession session, String reason) {
        mpcMetrics.decrementActive();
        statusExposer.removeSession(session.getSessionId());
    }

    public void onSessionTimeout(MpcSession session) {
        mpcMetrics.recordTimeout();
        mpcMetrics.decrementActive();
        statusExposer.removeSession(session.getSessionId());
    }
}
