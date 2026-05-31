package com.datamasker.interfaces.assembler;

import com.datamasker.domain.mpc.model.MpcComputationResult;
import com.datamasker.domain.mpc.model.MpcSession;
import com.datamasker.interfaces.dto.mpc.CreateSessionResponse;
import com.datamasker.interfaces.dto.mpc.MpcResultResponse;

public class MpcAssembler {

    public static CreateSessionResponse toCreateSessionResponse(MpcSession session) {
        CreateSessionResponse response = new CreateSessionResponse();
        response.setSessionId(session.getSessionId());
        response.setProtocolType(session.getProtocolType());
        response.setPartyCount(session.getPartyCount());
        response.setStatus(session.getStatus());
        return response;
    }

    public static MpcResultResponse toMpcResultResponse(MpcComputationResult result) {
        MpcResultResponse response = new MpcResultResponse();
        response.setSessionId(result.getSessionId());
        response.setResult(result.getResult());
        response.setParticipantCount(result.getParticipantCount());
        response.setVerified(result.isVerified());
        return response;
    }
}
