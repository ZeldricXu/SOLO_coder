package com.ratelimiter.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.model.CircuitBreakerState;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Repository
public class CircuitBreakerRepository {
    
    private static final String CIRCUIT_KEY_PREFIX = "ratelimiter:circuit:";
    private static final String CIRCUIT_INDEX_KEY = "ratelimiter:circuits:index";
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    
    public CircuitBreakerRepository(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }
    
    public void save(CircuitBreakerState circuitState) {
        String key = getCircuitKey(circuitState.getCircuitId());
        redisTemplate.opsForValue().set(key, circuitState);
        redisTemplate.opsForSet().add(CIRCUIT_INDEX_KEY, circuitState.getCircuitId());
    }
    
    public CircuitBreakerState findById(String circuitId) {
        String key = getCircuitKey(circuitId);
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        return objectMapper.convertValue(value, CircuitBreakerState.class);
    }
    
    public CircuitBreakerState findByServiceName(String serviceName) {
        Set<Object> circuitIds = redisTemplate.opsForSet().members(CIRCUIT_INDEX_KEY);
        if (circuitIds == null) {
            return null;
        }
        
        for (Object circuitIdObj : circuitIds) {
            String circuitId = (String) circuitIdObj;
            CircuitBreakerState circuit = findById(circuitId);
            if (circuit != null && 
                circuit.getServiceName() != null && 
                circuit.getServiceName().equals(serviceName)) {
                return circuit;
            }
        }
        return null;
    }
    
    public List<CircuitBreakerState> findAll() {
        List<CircuitBreakerState> circuits = new ArrayList<>();
        Set<Object> circuitIds = redisTemplate.opsForSet().members(CIRCUIT_INDEX_KEY);
        if (circuitIds == null) {
            return circuits;
        }
        
        for (Object circuitIdObj : circuitIds) {
            String circuitId = (String) circuitIdObj;
            CircuitBreakerState circuit = findById(circuitId);
            if (circuit != null) {
                circuits.add(circuit);
            }
        }
        return circuits;
    }
    
    public void deleteById(String circuitId) {
        String key = getCircuitKey(circuitId);
        redisTemplate.delete(key);
        redisTemplate.opsForSet().remove(CIRCUIT_INDEX_KEY, circuitId);
    }
    
    private String getCircuitKey(String circuitId) {
        return CIRCUIT_KEY_PREFIX + circuitId;
    }
}