package com.apishield.mpc.service;

import com.apishield.application.service.ApplicationService;
import com.apishield.mpc.domain.MpcSession;
import com.apishield.mpc.dto.MpcInputRequest;
import com.apishield.mpc.dto.MpcSessionRequest;
import java.util.Map;

public interface MpcCoordinatorService extends ApplicationService {
    MpcSession createSession(MpcSessionRequest request);
    MpcSession getSession(String sessionId);
    MpcSession startSession(String sessionId);
    void submitInput(MpcInputRequest request);
    MpcSession executeComputation(String sessionId);
    MpcSession cancelSession(String sessionId);
    Map<String, Object> getResult(String sessionId);
}
