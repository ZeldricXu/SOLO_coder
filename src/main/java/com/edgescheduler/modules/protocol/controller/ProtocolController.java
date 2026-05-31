package com.edgescheduler.modules.protocol.controller;

import com.edgescheduler.common.Result;
import com.edgescheduler.modules.protocol.domain.DataForwardRule;
import com.edgescheduler.modules.protocol.domain.ProtocolDriver;
import com.edgescheduler.modules.protocol.service.ProtocolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/protocol")
@RequiredArgsConstructor
public class ProtocolController {

    private final ProtocolService protocolService;

    @PostMapping("/drivers")
    public Mono<Result<ProtocolDriver>> registerDriver(@RequestBody ProtocolDriver driver) {
        return protocolService.registerDriver(driver)
                .map(Result::success);
    }

    @PostMapping("/drivers/{driverId}/load")
    public Mono<Result<ProtocolDriver>> loadDriver(@PathVariable String driverId) {
        return protocolService.loadDriver(driverId)
                .map(Result::success);
    }

    @PostMapping("/drivers/{driverId}/unload")
    public Mono<Result<ProtocolDriver>> unloadDriver(@PathVariable String driverId) {
        return protocolService.unloadDriver(driverId)
                .map(Result::success);
    }

    @GetMapping("/drivers")
    public Flux<Result<ProtocolDriver>> getDrivers(
            @RequestParam(required = false) String protocolType) {
        return protocolService.getDrivers(protocolType)
                .map(Result::success);
    }

    @PostMapping("/convert")
    public Mono<Result<Map<String, Object>>> convertData(
            @RequestParam String sourceProtocol,
            @RequestBody Map<String, Object> rawData) {
        return protocolService.convertData(sourceProtocol, rawData)
                .map(Result::success);
    }

    @PostMapping("/forward")
    public Mono<Result<Map<String, Object>>> forwardData(
            @RequestParam String sourceProtocol,
            @RequestParam String sourceTopic,
            @RequestBody Map<String, Object> normalizedData) {
        return protocolService.forwardData(sourceProtocol, sourceTopic, normalizedData)
                .map(Result::success);
    }

    @PostMapping("/forward-rules")
    public Mono<Result<DataForwardRule>> createForwardRule(@RequestBody DataForwardRule rule) {
        return protocolService.createForwardRule(rule)
                .map(Result::success);
    }

    @GetMapping("/forward-rules")
    public Flux<Result<DataForwardRule>> getForwardRules(
            @RequestParam(required = false) String sourceProtocol) {
        return protocolService.getForwardRules(sourceProtocol)
                .map(Result::success);
    }

    @PutMapping("/forward-rules/{ruleId}/toggle")
    public Mono<Result<DataForwardRule>> toggleForwardRule(
            @PathVariable String ruleId,
            @RequestParam boolean enabled) {
        return protocolService.toggleForwardRule(ruleId, enabled)
                .map(Result::success);
    }
}
