package com.servicedesk.strategy;

import com.servicedesk.entity.Agent;
import com.servicedesk.entity.Ticket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component("skill_match")
public class SkillMatchStrategy implements AssignmentStrategy {

    private static final Map<String, List<String>> CATEGORY_SKILLS = new HashMap<>();
    private static final Map<String, List<String>> GROUP_SKILLS = new HashMap<>();

    static {
        CATEGORY_SKILLS.put("technical", Arrays.asList("java", "system", "database", "network", "bug"));
        CATEGORY_SKILLS.put("billing", Arrays.asList("billing", "payment", "refund", "invoice"));
        CATEGORY_SKILLS.put("业务", Arrays.asList("business", "consulting", "process"));
        CATEGORY_SKILLS.put("业务咨询", Arrays.asList("business", "consulting", "process"));

        GROUP_SKILLS.put("technical_support", Arrays.asList("java", "system", "database", "network", "bug"));
        GROUP_SKILLS.put("billing_support", Arrays.asList("billing", "payment", "refund", "invoice"));
        GROUP_SKILLS.put("business_support", Arrays.asList("business", "consulting", "process"));
        GROUP_SKILLS.put("general_support", Arrays.asList("general", "basic"));
    }

    @Override
    public String getName() {
        return "skill_match";
    }

    @Override
    public String getDisplayName() {
        return "技能匹配";
    }

    @Override
    public Agent selectAgent(Ticket ticket, List<Agent> availableAgents, Map<String, Object> parameters) {
        log.debug("使用技能匹配策略选择客服，可用客服数量: {}", availableAgents.size());

        if (availableAgents == null || availableAgents.isEmpty()) {
            return null;
        }

        String ticketCategory = ticket.getTicketCategory();
        List<String> requiredSkills = CATEGORY_SKILLS.getOrDefault(
                ticketCategory != null ? ticketCategory.toLowerCase() : "general",
                Collections.emptyList()
        );

        Agent bestMatch = null;
        int bestScore = -1;

        for (Agent agent : availableAgents) {
            int score = calculateSkillMatchScore(agent, requiredSkills);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = agent;
            } else if (score == bestScore && bestMatch != null) {
                if (agent.getCurrentTickets() < bestMatch.getCurrentTickets()) {
                    bestMatch = agent;
                }
            }
        }

        return bestMatch != null ? bestMatch : availableAgents.get(0);
    }

    private int calculateSkillMatchScore(Agent agent, List<String> requiredSkills) {
        List<String> agentSkills = GROUP_SKILLS.getOrDefault(
                agent.getAgentGroup() != null ? agent.getAgentGroup() : "general_support",
                Collections.emptyList()
        );

        int score = 0;
        for (String skill : requiredSkills) {
            if (agentSkills.contains(skill)) {
                score += 10;
            }
        }

        score += (agent.getMaxTickets() - agent.getCurrentTickets());

        return score;
    }
}
