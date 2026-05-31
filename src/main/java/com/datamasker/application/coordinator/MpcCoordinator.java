package com.datamasker.application.coordinator;

import com.datamasker.application.service.MpcService;
import com.datamasker.domain.mpc.model.MpcComputationResult;
import com.datamasker.domain.mpc.model.MpcSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MpcCoordinator {

    private final MpcService mpcService;

    public MpcComputationResult coordinateSession(String sessionId) {
        if (!validateQuorum(sessionId)) {
            throw new IllegalStateException("Quorum not reached for session: " + sessionId);
        }

        MpcComputationResult result = mpcService.executeComputation(sessionId);

        if (!result.isVerified()) {
            throw new RuntimeException("Computation verification failed for session: " + sessionId);
        }

        return result;
    }

    public boolean validateQuorum(String sessionId) {
        MpcSession session = mpcService.findSessionOrThrow(sessionId);

        int committedParties = (int) session.getParties().stream()
                .filter(p -> p.isInputCommitted())
                .count();

        return committedParties >= session.getPartyCount();
    }
}
