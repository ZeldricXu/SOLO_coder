package com.enterprise.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.gateway.admin.mapper.CircuitBreakerRuleMapper;
import com.enterprise.gateway.common.model.CircuitBreakerRule;
import com.enterprise.gateway.ratelimit.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CircuitBreakerService {

    private final CircuitBreakerRuleMapper circuitBreakerRuleMapper;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CircuitBreakerRule getByRouteId(String routeId) {
        return circuitBreakerRuleMapper.selectOne(new LambdaQueryWrapper<CircuitBreakerRule>()
                .eq(CircuitBreakerRule::getRouteId, routeId));
    }

    public String getState(String routeId) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.get(routeId);
        if (circuitBreaker == null) {
            return "CLOSED";
        }
        return circuitBreaker.getState().name();
    }

    public void resetCircuitBreaker(String routeId) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.get(routeId);
        if (circuitBreaker != null) {
            circuitBreaker.reset();
            log.info("Circuit breaker reset for route: {}", routeId);
        }
    }

    public CircuitBreakerRule createOrUpdate(CircuitBreakerRule rule) {
        if (rule.getId() == null) {
            rule.setCreatedAt(LocalDateTime.now());
            rule.setUpdatedAt(LocalDateTime.now());
            if (rule.getStatus() == null) {
                rule.setStatus(1);
            }
            circuitBreakerRuleMapper.insert(rule);
        } else {
            rule.setUpdatedAt(LocalDateTime.now());
            circuitBreakerRuleMapper.updateById(rule);
        }
        refreshRegistry();
        return rule;
    }

    public void deleteRule(Long id) {
        CircuitBreakerRule rule = circuitBreakerRuleMapper.selectById(id);
        if (rule != null) {
            circuitBreakerRuleMapper.deleteById(id);
            circuitBreakerRegistry.remove(rule.getRouteId());
            refreshRegistry();
        }
    }

    private void refreshRegistry() {
        List<CircuitBreakerRule> rules = circuitBreakerRuleMapper.selectList(new LambdaQueryWrapper<CircuitBreakerRule>()
                .eq(CircuitBreakerRule::getStatus, 1));
        circuitBreakerRegistry.refreshAll(rules);
    }
}
