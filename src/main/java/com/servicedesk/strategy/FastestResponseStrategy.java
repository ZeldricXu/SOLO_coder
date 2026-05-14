package com.servicedesk.strategy;

import com.servicedesk.entity.Agent;
import com.servicedesk.entity.Ticket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Component("fastest_response")
public class FastestResponseStrategy implements AssignmentStrategy {

    @Override
    public String getName() {
        return "fastest_response";
    }

    @Override
    public String getDisplayName() {
        return "响应最快优先";
    }

    @Override
    public Agent selectAgent(Ticket ticket, List<Agent> availableAgents, Map<String, Object> parameters) {
        log.debug("使用响应最快优先策略选择客服，可用客服数量: {}", availableAgents.size());

        if (availableAgents == null || availableAgents.isEmpty()) {
            return null;
        }

        return availableAgents.stream()
                .min(Comparator.comparing(Agent::getResponseAvgTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(availableAgents.get(0));
    }
}
