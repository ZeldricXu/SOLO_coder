package com.apishield.mpc.protocol;

import com.apishield.common.exception.BusinessException;
import com.apishield.mpc.domain.MpcSession;
import com.apishield.mpc.participant.MpcParticipant;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractMpcProtocol implements MpcProtocol {

    @Override
    public void initialize(MpcSession session, List<MpcParticipant> participants) {
        if (participants.size() < getMinParticipants() || participants.size() > getMaxParticipants()) {
            throw new BusinessException("MPC_001", 
                String.format("参与方数量不合法，需要%d-%d个，实际%d个", 
                    getMinParticipants(), getMaxParticipants(), participants.size()));
        }
        log.info("Initializing protocol {} with {} participants", getProtocolName(), participants.size());
        doInitialize(session, participants);
    }

    protected abstract void doInitialize(MpcSession session, List<MpcParticipant> participants);

    @Override
    public void executeRound(MpcSession session, int roundNumber, Map<String, Object> roundData) {
        log.debug("Executing round {} for session {}", roundNumber, session.getSessionId());
        doExecuteRound(session, roundNumber, roundData);
    }

    protected abstract void doExecuteRound(MpcSession session, int roundNumber, Map<String, Object> roundData);
}
