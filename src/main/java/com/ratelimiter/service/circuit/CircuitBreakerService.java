package com.ratelimiter.service.circuit;

import com.ratelimiter.model.CircuitBreakerState;
import com.ratelimiter.model.CircuitBreakerState.CircuitState;
import com.ratelimiter.repository.CircuitBreakerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
public class CircuitBreakerService {
    
    private final CircuitBreakerRepository circuitBreakerRepository;
    private final CircuitNotificationService notificationService;
    
    private static final int DEFAULT_FAILURE_THRESHOLD = 10;
    private static final int DEFAULT_SUCCESS_THRESHOLD = 5;
    private static final long DEFAULT_TIMEOUT_MS = 30000;
    
    public CircuitBreakerService(CircuitBreakerRepository circuitBreakerRepository,
                                  CircuitNotificationService notificationService) {
        this.circuitBreakerRepository = circuitBreakerRepository;
        this.notificationService = notificationService;
    }
    
    public CircuitBreakerState createCircuit(String circuitId, String serviceName) {
        return createCircuit(circuitId, serviceName, DEFAULT_FAILURE_THRESHOLD, 
                DEFAULT_SUCCESS_THRESHOLD, DEFAULT_TIMEOUT_MS);
    }
    
    public CircuitBreakerState createCircuit(String circuitId, String serviceName,
                                               int failureThreshold, int successThreshold, long timeoutMs) {
        CircuitBreakerState existing = circuitBreakerRepository.findById(circuitId);
        if (existing != null) {
            log.info("Circuit breaker already exists for id: {}", circuitId);
            return existing;
        }
        
        CircuitBreakerState state = CircuitBreakerState.builder()
                .circuitId(circuitId)
                .serviceName(serviceName)
                .state(CircuitState.CLOSED)
                .failureCount(0)
                .successCount(0)
                .failureThreshold(failureThreshold)
                .successThreshold(successThreshold)
                .timeoutMs(timeoutMs)
                .lastStateChange(Instant.now())
                .build();
        
        circuitBreakerRepository.save(state);
        log.info("Created circuit breaker: {} for service: {}", circuitId, serviceName);
        return state;
    }
    
    public CircuitBreakerState getCircuitState(String circuitId) {
        return circuitBreakerRepository.findById(circuitId);
    }
    
    public CircuitBreakerState getCircuitStateByService(String serviceName) {
        return circuitBreakerRepository.findByServiceName(serviceName);
    }
    
    public List<CircuitBreakerState> getAllCircuits() {
        return circuitBreakerRepository.findAll();
    }
    
    public boolean isAllowed(String circuitId) {
        CircuitBreakerState state = circuitBreakerRepository.findById(circuitId);
        if (state == null) {
            return true;
        }
        
        return isAllowed(state);
    }
    
    public boolean isAllowed(CircuitBreakerState state) {
        if (state == null) {
            return true;
        }
        
        CircuitState currentState = state.getState();
        
        switch (currentState) {
            case CLOSED:
                return true;
            case OPEN:
                checkAndTransitionToHalfOpen(state);
                return state.getState() == CircuitState.HALF_OPEN;
            case HALF_OPEN:
                return true;
            default:
                return true;
        }
    }
    
    public void recordSuccess(String circuitId) {
        CircuitBreakerState state = circuitBreakerRepository.findById(circuitId);
        if (state == null) {
            return;
        }
        
        boolean stateChanged = false;
        CircuitState fromState = state.getState();
        
        switch (state.getState()) {
            case CLOSED:
                state.setSuccessCount(state.getSuccessCount() + 1);
                state.setFailureCount(0);
                break;
            case HALF_OPEN:
                state.setSuccessCount(state.getSuccessCount() + 1);
                if (state.getSuccessCount() >= state.getSuccessThreshold()) {
                    transitionToClosed(state);
                    stateChanged = true;
                }
                break;
            case OPEN:
                break;
        }
        
        circuitBreakerRepository.save(state);
        
        if (stateChanged) {
            notifyStateChange(state.getCircuitId(), state.getServiceName(), 
                    fromState, state.getState(), 
                    state.getFailureCount(), state.getSuccessCount(),
                    "Success threshold reached in HALF_OPEN state");
        }
        
        log.debug("Recorded success for circuit: {}, state: {}", circuitId, state.getState());
    }
    
    public void recordFailure(String circuitId) {
        CircuitBreakerState state = circuitBreakerRepository.findById(circuitId);
        if (state == null) {
            return;
        }
        
        boolean stateChanged = false;
        CircuitState fromState = state.getState();
        String reason = "";
        
        switch (state.getState()) {
            case CLOSED:
                state.setFailureCount(state.getFailureCount() + 1);
                state.setSuccessCount(0);
                if (state.getFailureCount() >= state.getFailureThreshold()) {
                    transitionToOpen(state);
                    stateChanged = true;
                    reason = "Failure threshold reached in CLOSED state";
                }
                break;
            case HALF_OPEN:
                transitionToOpen(state);
                stateChanged = true;
                reason = "Failure occurred in HALF_OPEN state";
                break;
            case OPEN:
                break;
        }
        
        circuitBreakerRepository.save(state);
        
        if (stateChanged) {
            notifyStateChange(state.getCircuitId(), state.getServiceName(),
                    fromState, state.getState(),
                    state.getFailureCount(), state.getSuccessCount(),
                    reason);
        }
        
        log.warn("Recorded failure for circuit: {}, state: {}, failureCount: {}", 
                circuitId, state.getState(), state.getFailureCount());
    }
    
    public DegradedResponse generateDegradedResponse(CircuitBreakerState state) {
        return DegradedResponse.builder()
                .code(503)
                .message("Service " + state.getServiceName() + " is currently unavailable (circuit breaker open)")
                .retryAfterMs(state.getTimeoutMs())
                .build();
    }
    
    private void checkAndTransitionToHalfOpen(CircuitBreakerState state) {
        if (state.getState() != CircuitState.OPEN) {
            return;
        }
        
        long elapsedMs = Instant.now().toEpochMilli() - state.getLastStateChange().toEpochMilli();
        if (elapsedMs >= state.getTimeoutMs()) {
            CircuitState fromState = state.getState();
            transitionToHalfOpen(state);
            circuitBreakerRepository.save(state);
            
            notifyStateChange(state.getCircuitId(), state.getServiceName(),
                    fromState, state.getState(),
                    state.getFailureCount(), state.getSuccessCount(),
                    "Timeout reached, transitioning to HALF_OPEN");
            
            log.info("Circuit {} transitioned from OPEN to HALF_OPEN after timeout", state.getCircuitId());
        }
    }
    
    private void transitionToOpen(CircuitBreakerState state) {
        if (state.getState() == CircuitState.OPEN) {
            return;
        }
        state.setState(CircuitState.OPEN);
        state.setLastStateChange(Instant.now());
        log.warn("Circuit {} transitioned to OPEN state. Failure count: {}", 
                state.getCircuitId(), state.getFailureCount());
    }
    
    private void transitionToHalfOpen(CircuitBreakerState state) {
        if (state.getState() == CircuitState.HALF_OPEN) {
            return;
        }
        state.setState(CircuitState.HALF_OPEN);
        state.setSuccessCount(0);
        state.setFailureCount(0);
        state.setLastStateChange(Instant.now());
        log.info("Circuit {} transitioned to HALF_OPEN state", state.getCircuitId());
    }
    
    private void transitionToClosed(CircuitBreakerState state) {
        if (state.getState() == CircuitState.CLOSED) {
            return;
        }
        state.setState(CircuitState.CLOSED);
        state.setSuccessCount(0);
        state.setFailureCount(0);
        state.setLastStateChange(Instant.now());
        log.info("Circuit {} transitioned to CLOSED state", state.getCircuitId());
    }
    
    private void notifyStateChange(String circuitId, String serviceName,
                                     CircuitState fromState, CircuitState toState,
                                     int failureCount, int successCount,
                                     String reason) {
        CircuitStateChangeEvent event = CircuitStateChangeEvent.create(
                circuitId, serviceName, fromState, toState,
                failureCount, successCount, reason
        );
        notificationService.notifyStateChange(event);
    }
    
    public void registerCircuitCallback(String circuitId, String callbackUrl) {
        notificationService.registerCircuitCallback(circuitId, callbackUrl);
    }
    
    public void unregisterCircuitCallback(String circuitId, String callbackUrl) {
        notificationService.unregisterCircuitCallback(circuitId, callbackUrl);
    }
    
    public void registerGlobalCallback(String callbackUrl) {
        notificationService.registerGlobalCallback(callbackUrl);
    }
    
    public void unregisterGlobalCallback(String callbackUrl) {
        notificationService.unregisterGlobalCallback(callbackUrl);
    }
    
    public List<String> getCircuitCallbacks(String circuitId) {
        return notificationService.getCircuitCallbacks(circuitId);
    }
    
    public List<String> getGlobalCallbacks() {
        return notificationService.getGlobalCallbacks();
    }
    
    public void resetCircuit(String circuitId) {
        CircuitBreakerState state = circuitBreakerRepository.findById(circuitId);
        if (state != null) {
            CircuitState fromState = state.getState();
            state.setState(CircuitState.CLOSED);
            state.setSuccessCount(0);
            state.setFailureCount(0);
            state.setLastStateChange(Instant.now());
            circuitBreakerRepository.save(state);
            
            if (fromState != CircuitState.CLOSED) {
                notifyStateChange(circuitId, state.getServiceName(),
                        fromState, CircuitState.CLOSED,
                        0, 0, "Circuit manually reset");
            }
            
            log.info("Circuit {} has been reset to CLOSED state", circuitId);
        }
    }
    
    public void deleteCircuit(String circuitId) {
        notificationService.unregisterAllCallbacksForCircuit(circuitId);
        circuitBreakerRepository.deleteById(circuitId);
        log.info("Circuit {} has been deleted", circuitId);
    }
}