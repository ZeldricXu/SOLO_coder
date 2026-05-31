package com.apishield.mpc.protocol;

import com.apishield.mpc.domain.MpcSession;
import com.apishield.mpc.participant.MpcParticipant;
import java.util.List;
import java.util.Map;

public interface MpcProtocol {
    String getProtocolName();
    int getMinParticipants();
    int getMaxParticipants();
    
    void initialize(MpcSession session, List<MpcParticipant> participants);
    void executeRound(MpcSession session, int roundNumber, Map<String, Object> roundData);
    Map<String, Object> computeResult(MpcSession session, List<Map<String, Object>> allInputs);
    boolean isCompleted(MpcSession session);
}
