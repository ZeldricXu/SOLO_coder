package com.apishield.mpc.controller;

import com.apishield.common.dto.Result;
import com.apishield.mpc.domain.MpcSession;
import com.apishield.mpc.dto.MpcInputRequest;
import com.apishield.mpc.dto.MpcSessionRequest;
import com.apishield.mpc.service.MpcCoordinatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/mpc")
@RequiredArgsConstructor
public class MpcController {

    private final MpcCoordinatorService mpcCoordinatorService;

    @PostMapping("/sessions")
    public Mono<Result<MpcSession>> createSession(@RequestBody MpcSessionRequest request) {
        return Mono.just(Result.success(mpcCoordinatorService.createSession(request)));
    }

    @GetMapping("/sessions/{sessionId}")
    public Mono<Result<MpcSession>> getSession(@PathVariable String sessionId) {
        return Mono.just(Result.success(mpcCoordinatorService.getSession(sessionId)));
    }

    @PostMapping("/sessions/{sessionId}/start")
    public Mono<Result<MpcSession>> startSession(@PathVariable String sessionId) {
        return Mono.just(Result.success(mpcCoordinatorService.startSession(sessionId)));
    }

    @PostMapping("/sessions/{sessionId}/input")
    public Mono<Result<Void>> submitInput(@RequestBody MpcInputRequest request) {
        mpcCoordinatorService.submitInput(request);
        return Mono.just(Result.success(null));
    }

    @PostMapping("/sessions/{sessionId}/execute")
    public Mono<Result<MpcSession>> executeComputation(@PathVariable String sessionId) {
        return Mono.just(Result.success(mpcCoordinatorService.executeComputation(sessionId)));
    }

    @PostMapping("/sessions/{sessionId}/cancel")
    public Mono<Result<MpcSession>> cancelSession(@PathVariable String sessionId) {
        return Mono.just(Result.success(mpcCoordinatorService.cancelSession(sessionId)));
    }

    @GetMapping("/sessions/{sessionId}/result")
    public Mono<Result<Map<String, Object>>> getResult(@PathVariable String sessionId) {
        return Mono.just(Result.success(mpcCoordinatorService.getResult(sessionId)));
    }
}
