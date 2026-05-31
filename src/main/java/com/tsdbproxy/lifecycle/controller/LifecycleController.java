package com.tsdbproxy.lifecycle.controller;

import com.tsdbproxy.common.entity.LifecyclePolicy;
import com.tsdbproxy.common.result.Result;
import com.tsdbproxy.lifecycle.dto.LifecycleExecuteRequest;
import com.tsdbproxy.lifecycle.dto.LifecyclePolicyCreateRequest;
import com.tsdbproxy.lifecycle.service.LifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/lifecycle")
@RequiredArgsConstructor
public class LifecycleController {

    private final LifecycleService lifecycleService;

    @PostMapping("/policies")
    public Mono<Result<LifecyclePolicy>> createPolicy(@RequestBody LifecyclePolicyCreateRequest request) {
        return lifecycleService.createPolicy(request)
                .map(Result::success);
    }

    @PostMapping("/execute")
    public Mono<Result<Void>> execute(@RequestBody LifecycleExecuteRequest request) {
        return lifecycleService.executePolicy(request)
                .then(Mono.just(Result.success()));
    }
}
