package com.servicedesk.strategy;

import com.servicedesk.entity.Agent;
import com.servicedesk.entity.Ticket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component("round_robin")
public class RoundRobinStrategy implements AssignmentStrategy {

    private final Map<String, AtomicInteger> groupCounters = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "round_robin";
    }

    @Override
    public String getDisplayName() {
        return "轮询分配";
    }

    @Override
    public Agent selectAgent(Ticket ticket, List<Agent> availableAgents, Map<String, Object> parameters) {
        log.debug("使用轮询分配策略选择客服，可用客服数量: {}", availableAgents.size());

        if (availableAgents == null || availableAgents.isEmpty()) {
            return null;
        }

        String groupKey = ticket.getTicketCategory();
        if (groupKey == null) {
            groupKey = "default";
        }

        AtomicInteger counter = groupCounters.computeIfAbsent(groupKey, k -> new AtomicInteger(0));
        int index = Math.abs(counter.getAndIncrement()) % availableAgents.size();

        return availableAgents.get(index);
    }

    public void resetCounters() {
        groupCounters.clear();
    }

    public int getCounter(String group) {
        AtomicInteger counter = groupCounters.get(group);
        return counter != null ? counter.get() : 0;
    }
}
