package com.ratelimiter.controller;

import com.ratelimiter.model.CircuitBreakerState;
import com.ratelimiter.model.dto.ApiResponse;
import com.ratelimiter.model.dto.CircuitStatusResponse;
import com.ratelimiter.service.circuit.CircuitBreakerService;
import com.ratelimiter.service.circuit.DegradedResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/circuit")
public class CircuitBreakerController {
    
    private final CircuitBreakerService circuitBreakerService;
    
    public CircuitBreakerController(CircuitBreakerService circuitBreakerService) {
        this.circuitBreakerService = circuitBreakerService;
    }
    
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<CircuitStatusResponse>> getCircuitStatus(
            @RequestParam(value = "circuitId", required = false) String circuitId,
            @RequestParam(value = "serviceName", required = false) String serviceName) {
        
        CircuitBreakerState state = null;
        
        if (circuitId != null && !circuitId.isEmpty()) {
            state = circuitBreakerService.getCircuitState(circuitId);
        } else if (serviceName != null && !serviceName.isEmpty()) {
            state = circuitBreakerService.getCircuitStateByService(serviceName);
        }
        
        if (state == null) {
            return ResponseEntity.ok(ApiResponse.success(
                    CircuitStatusResponse.builder()
                            .state(CircuitBreakerState.CircuitState.CLOSED)
                            .failureCount(0)
                            .successCount(0)
                            .build()
            ));
        }
        
        CircuitStatusResponse response = CircuitStatusResponse.builder()
                .state(state.getState())
                .failureCount(state.getFailureCount())
                .successCount(state.getSuccessCount())
                .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<CircuitBreakerState>> createCircuit(
            @RequestBody CircuitBreakerState request) {
        
        CircuitBreakerState circuit = circuitBreakerService.createCircuit(
                request.getCircuitId(),
                request.getServiceName(),
                request.getFailureThreshold() > 0 ? request.getFailureThreshold() : 10,
                request.getSuccessThreshold() > 0 ? request.getSuccessThreshold() : 5,
                request.getTimeoutMs() > 0 ? request.getTimeoutMs() : 30000
        );
        
        return ResponseEntity.ok(ApiResponse.success(circuit));
    }
    
    @GetMapping("/all")
    public ResponseEntity<ApiResponse<List<CircuitBreakerState>>> getAllCircuits() {
        List<CircuitBreakerState> circuits = circuitBreakerService.getAllCircuits();
        return ResponseEntity.ok(ApiResponse.success(circuits));
    }
    
    @PostMapping("/check")
    public ResponseEntity<ApiResponse<Boolean>> checkAllowed(@RequestParam String circuitId) {
        boolean allowed = circuitBreakerService.isAllowed(circuitId);
        return ResponseEntity.ok(ApiResponse.success(allowed));
    }
    
    @PostMapping("/success")
    public ResponseEntity<ApiResponse<Void>> recordSuccess(@RequestParam String circuitId) {
        circuitBreakerService.recordSuccess(circuitId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    
    @PostMapping("/failure")
    public ResponseEntity<ApiResponse<Void>> recordFailure(@RequestParam String circuitId) {
        circuitBreakerService.recordFailure(circuitId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    
    @PostMapping("/reset")
    public ResponseEntity<ApiResponse<Void>> resetCircuit(@RequestParam String circuitId) {
        circuitBreakerService.resetCircuit(circuitId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    
    @DeleteMapping("/{circuitId}")
    public ResponseEntity<ApiResponse<Void>> deleteCircuit(@PathVariable String circuitId) {
        circuitBreakerService.deleteCircuit(circuitId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    
    @GetMapping("/degraded")
    public ResponseEntity<ApiResponse<DegradedResponse>> getDegradedResponse(
            @RequestParam String circuitId) {
        CircuitBreakerState state = circuitBreakerService.getCircuitState(circuitId);
        if (state == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Circuit not found"));
        }
        
        DegradedResponse response = circuitBreakerService.generateDegradedResponse(state);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    @PostMapping("/callback/global/register")
    public ResponseEntity<ApiResponse<Void>> registerGlobalCallback(
            @RequestParam String callbackUrl) {
        circuitBreakerService.registerGlobalCallback(callbackUrl);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    
    @PostMapping("/callback/global/unregister")
    public ResponseEntity<ApiResponse<Void>> unregisterGlobalCallback(
            @RequestParam String callbackUrl) {
        circuitBreakerService.unregisterGlobalCallback(callbackUrl);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    
    @GetMapping("/callback/global")
    public ResponseEntity<ApiResponse<List<String>>> getGlobalCallbacks() {
        List<String> callbacks = circuitBreakerService.getGlobalCallbacks();
        return ResponseEntity.ok(ApiResponse.success(callbacks));
    }
    
    @PostMapping("/callback/{circuitId}/register")
    public ResponseEntity<ApiResponse<Void>> registerCircuitCallback(
            @PathVariable String circuitId,
            @RequestParam String callbackUrl) {
        circuitBreakerService.registerCircuitCallback(circuitId, callbackUrl);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    
    @PostMapping("/callback/{circuitId}/unregister")
    public ResponseEntity<ApiResponse<Void>> unregisterCircuitCallback(
            @PathVariable String circuitId,
            @RequestParam String callbackUrl) {
        circuitBreakerService.unregisterCircuitCallback(circuitId, callbackUrl);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    
    @GetMapping("/callback/{circuitId}")
    public ResponseEntity<ApiResponse<List<String>>> getCircuitCallbacks(
            @PathVariable String circuitId) {
        List<String> callbacks = circuitBreakerService.getCircuitCallbacks(circuitId);
        return ResponseEntity.ok(ApiResponse.success(callbacks));
    }
}