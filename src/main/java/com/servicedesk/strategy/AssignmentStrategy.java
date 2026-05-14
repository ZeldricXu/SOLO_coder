package com.servicedesk.strategy;

import com.servicedesk.entity.Agent;
import com.servicedesk.entity.Ticket;

import java.util.List;
import java.util.Map;

public interface AssignmentStrategy {

    String getName();

    String getDisplayName();

    Agent selectAgent(Ticket ticket, List<Agent> availableAgents, Map<String, Object> parameters);

    default boolean isEnabled() {
        return true;
    }
}
