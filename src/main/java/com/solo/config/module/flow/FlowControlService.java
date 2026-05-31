package com.solo.config.module.flow;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.solo.config.common.IdGenerator;
import com.solo.config.common.exception.BusinessException;
import com.solo.config.entity.FlowPolicy;
import com.solo.config.mapper.FlowPolicyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlowControlService {

    private final FlowPolicyMapper flowPolicyMapper;
    private final FlowProperties properties;

    private final Map<String, CircuitBreakerState> circuitBreakers = new ConcurrentHashMap<>();

    public Mono<String> routeCanary(String serviceName, Map<String, String> headers) {
        return Mono.fromCallable(() -> {
            if (!properties.getCanary().isEnabled()) {
                return "stable";
            }

            String canaryHeader = headers.get("X-Canary");
            if ("true".equals(canaryHeader)) {
                return "canary";
            }

            int random = (int) (Math.random() * 100);
            if (random < properties.getCanary().getDefaultWeight()) {
                return "canary";
            }
            return "stable";
        });
    }

    public Mono<String> routeBlueGreen(String serviceName) {
        return Mono.fromCallable(() -> {
            if (!properties.getBlueGreen().isEnabled()) {
                return "blue";
            }
            return properties.getBlueGreen().getActiveColor();
        });
    }

    public Mono<Boolean> shouldMirror(String serviceName) {
        return Mono.just(properties.getMirroring().isEnabled());
    }

    public Mono<Boolean> allowRequest(String serviceName) {
        return Mono.fromCallable(() -> {
            CircuitBreakerState state = circuitBreakers.computeIfAbsent(serviceName, k -> new CircuitBreakerState());

            if (state.status == CircuitBreakerStatus.OPEN) {
                if (System.currentTimeMillis() - state.lastFailureTime > properties.getCircuitBreaker().getWaitDurationInOpenState()) {
                    state.status = CircuitBreakerStatus.HALF_OPEN;
                    state.halfOpenCalls.set(0);
                    log.info("Circuit breaker half-open for service: {}", serviceName);
                } else {
                    log.warn("Circuit breaker open for service: {}", serviceName);
                    return false;
                }
            }

            if (state.status == CircuitBreakerStatus.HALF_OPEN) {
                if (state.halfOpenCalls.incrementAndGet() > properties.getCircuitBreaker().getPermittedNumberOfCallsInHalfOpenState()) {
                    return false;
                }
            }

            return true;
        });
    }

    public void recordSuccess(String serviceName) {
        CircuitBreakerState state = circuitBreakers.get(serviceName);
        if (state != null) {
            state.successCount.incrementAndGet();
            if (state.status == CircuitBreakerStatus.HALF_OPEN) {
                state.status = CircuitBreakerStatus.CLOSED;
                state.failureCount.set(0);
                state.successCount.set(0);
                log.info("Circuit breaker closed for service: {}", serviceName);
            }
            checkAndUpdateState(serviceName, state);
        }
    }

    public void recordFailure(String serviceName) {
        CircuitBreakerState state = circuitBreakers.get(serviceName);
        if (state != null) {
            state.failureCount.incrementAndGet();
            state.lastFailureTime = System.currentTimeMillis();
            checkAndUpdateState(serviceName, state);
        }
    }

    private void checkAndUpdateState(String serviceName, CircuitBreakerState state) {
        int total = state.successCount.get() + state.failureCount.get();
        if (total >= properties.getCircuitBreaker().getSlidingWindowSize()) {
            int failureRate = (state.failureCount.get() * 100) / total;
            if (failureRate >= properties.getCircuitBreaker().getFailureRateThreshold()) {
                state.status = CircuitBreakerStatus.OPEN;
                state.lastFailureTime = System.currentTimeMillis();
                log.warn("Circuit breaker opened for service: {}, failureRate: {}%", serviceName, failureRate);
            }
            state.successCount.set(0);
            state.failureCount.set(0);
        }
    }

    public Mono<FlowPolicy> createPolicy(FlowPolicy policy) {
        return Mono.fromCallable(() -> {
            policy.setPolicyId(IdGenerator.generatePolicyId());
            flowPolicyMapper.insert(policy);
            log.info("Flow policy created: {}", policy.getPolicyId());
            return policy;
        });
    }

    public Flux<FlowPolicy> listPolicies(String type) {
        return Flux.fromIterable(
                flowPolicyMapper.selectList(
                        new QueryWrapper<FlowPolicy>()
                                .eq(type != null, "type", type)
                                .orderByDesc("created_at")
                )
        );
    }

    public Mono<FlowPolicy> getPolicy(String policyId) {
        return Mono.justOrEmpty(
                flowPolicyMapper.selectOne(
                        new QueryWrapper<FlowPolicy>().eq("policy_id", policyId)
                )
        );
    }

    public Mono<FlowPolicy> updatePolicy(String policyId, FlowPolicy policy) {
        return Mono.fromCallable(() -> {
            FlowPolicy existing = flowPolicyMapper.selectOne(
                    new QueryWrapper<FlowPolicy>().eq("policy_id", policyId)
            );
            if (existing == null) {
                throw new BusinessException("Policy not found: " + policyId);
            }
            if (policy.getName() != null) {
                existing.setName(policy.getName());
            }
            if (policy.getConfig() != null) {
                existing.setConfig(policy.getConfig());
            }
            if (policy.getEnabled() != null) {
                existing.setEnabled(policy.getEnabled());
            }
            flowPolicyMapper.updateById(existing);
            return existing;
        });
    }

    public Mono<Void> deletePolicy(String policyId) {
        return Mono.fromRunnable(() -> {
            FlowPolicy policy = flowPolicyMapper.selectOne(
                    new QueryWrapper<FlowPolicy>().eq("policy_id", policyId)
            );
            if (policy != null) {
                flowPolicyMapper.deleteById(policy.getId());
            }
        });
    }

    public Mono<Map<String, Object>> getCircuitBreakerStatus(String serviceName) {
        return Mono.fromCallable(() -> {
            CircuitBreakerState state = circuitBreakers.get(serviceName);
            if (state == null) {
                return Map.of("status", "CLOSED");
            }
            return Map.of(
                    "status", state.status.name(),
                    "successCount", state.successCount.get(),
                    "failureCount", state.failureCount.get(),
                    "lastFailureTime", state.lastFailureTime
            );
        });
    }

    private static class CircuitBreakerState {
        CircuitBreakerStatus status = CircuitBreakerStatus.CLOSED;
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);
        final AtomicInteger halfOpenCalls = new AtomicInteger(0);
        volatile long lastFailureTime = 0;
    }

    private enum CircuitBreakerStatus {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
