package com.servicedesk.strategy;

import com.servicedesk.entity.Agent;
import com.servicedesk.entity.Ticket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Component("load_balanced")
public class LoadBalancedStrategy implements AssignmentStrategy {

    @Override
    public String getName() {
        return "load_balanced";
    }

    @Override
    public String getDisplayName() {
        return "负载均衡";
    }

    @Override
    public Agent selectAgent(Ticket ticket, List<Agent> availableAgents, Map<String, Object> parameters) {
        log.debug("使用负载均衡策略选择客服，可用客服数量: {}", availableAgents.size());

        if (availableAgents == null || availableAgents.isEmpty()) {
            return null;
        }

        return availableAgents.stream()
                .max(Comparator.comparing(Agent::getRemainingCapacity))
                .orElse(availableAgents.get(0));
    }
}
