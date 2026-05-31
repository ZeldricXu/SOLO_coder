package com.datamasker.interfaces.controller;

import com.datamasker.application.coordinator.MpcCoordinator;
import com.datamasker.application.service.MpcService;
import com.datamasker.domain.mpc.model.MpcComputationResult;
import com.datamasker.domain.mpc.model.MpcSession;
import com.datamasker.interfaces.dto.Result;
import com.datamasker.interfaces.dto.mpc.CreateSessionRequest;
import com.datamasker.interfaces.dto.mpc.CreateSessionResponse;
import com.datamasker.interfaces.dto.mpc.MpcResultResponse;
import com.datamasker.interfaces.dto.mpc.SubmitInputRequest;
import com.datamasker.interfaces.assembler.MpcAssembler;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mpc")
@RequiredArgsConstructor
public class MpcController {

    private final MpcService mpcService;
    private final MpcCoordinator mpcCoordinator;

    @PostMapping("/sessions")
    public Result<CreateSessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request) {
        MpcSession session = mpcService.createSession(request.getProtocolType(), request.getPartyCount());
        return Result.success(MpcAssembler.toCreateSessionResponse(session));
    }

    @PostMapping("/sessions/{sessionId}/inputs")
    public Result<Void> submitInput(@PathVariable String sessionId,
                                    @Valid @RequestBody SubmitInputRequest request) {
        mpcService.submitInput(sessionId, request.getPartyId(), request.getEncryptedInput());
        return Result.success(null);
    }

    @PostMapping("/sessions/{sessionId}/execute")
    public Result<MpcResultResponse> execute(@PathVariable String sessionId) {
        MpcComputationResult result = mpcCoordinator.coordinateSession(sessionId);
        return Result.success(MpcAssembler.toMpcResultResponse(result));
    }

    @GetMapping("/sessions/{sessionId}/result")
    public Result<MpcResultResponse> getResult(@PathVariable String sessionId) {
        MpcComputationResult result = mpcService.getResult(sessionId);
        return Result.success(MpcAssembler.toMpcResultResponse(result));
    }
}
