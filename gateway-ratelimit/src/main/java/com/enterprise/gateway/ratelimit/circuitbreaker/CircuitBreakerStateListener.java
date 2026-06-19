package com.enterprise.gateway.ratelimit.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerStateListener {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public void registerListener(String routeId) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.get(routeId);
        if (circuitBreaker != null) {
            circuitBreaker.getEventPublisher()
                    .onStateTransition(this::handleStateTransition)
                    .onError(event -> log.error("Circuit breaker error for route: {}, error: {}",
                            routeId, event.getThrowable().getMessage()))
                    .onSuccess(event -> log.debug("Circuit breaker success for route: {}", routeId))
                    .onCallNotPermitted(event -> log.warn("Circuit breaker call not permitted for route: {}", routeId));
            log.info("Registered state listener for circuit breaker: {}", routeId);
        }
    }

    private void handleStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        String routeId = event.getCircuitBreakerName();
        CircuitBreaker.State fromState = event.getStateTransition().getFromState();
        CircuitBreaker.State toState = event.getStateTransition().getToState();

        log.info("Circuit breaker state transition for route: {} from {} to {}",
                routeId, fromState, toState);

        StateChangeEvent stateChangeEvent = new StateChangeEvent(routeId, fromState, toState);
        eventPublisher.publishEvent(stateChangeEvent);

        if (toState == CircuitBreaker.State.OPEN) {
            sendAlert(routeId, fromState, toState);
        }
    }

    private void sendAlert(String routeId, CircuitBreaker.State fromState, CircuitBreaker.State toState) {
        log.error("ALERT: Circuit breaker opened for route: {}. Transitioned from {} to {}",
                routeId, fromState, toState);
    }

    public static class StateChangeEvent {
        private final String routeId;
        private final CircuitBreaker.State fromState;
        private final CircuitBreaker.State toState;
        private final long timestamp;

        public StateChangeEvent(String routeId, CircuitBreaker.State fromState, CircuitBreaker.State toState) {
            this.routeId = routeId;
            this.fromState = fromState;
            this.toState = toState;
            this.timestamp = System.currentTimeMillis();
        }

        public String getRouteId() {
            return routeId;
        }

        public CircuitBreaker.State getFromState() {
            return fromState;
        }

        public CircuitBreaker.State getToState() {
            return toState;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
