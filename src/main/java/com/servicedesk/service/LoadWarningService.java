package com.servicedesk.service;

import com.servicedesk.config.ServiceDeskProperties;
import com.servicedesk.entity.Agent;
import com.servicedesk.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoadWarningService {

    private final AgentRepository agentRepository;
    private final ServiceDeskProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    private final Set<String> warnedAgents = Collections.synchronizedSet(new HashSet<>());

    public double getWarningThreshold(String group, int teamSize) {
        return properties.getLoadWarning().getThresholdByGroup(group, teamSize);
    }

    public boolean isLoadWarningTriggered(Agent agent) {
        if (agent.getMaxTickets() <= 0) {
            return false;
        }
        double usageRatio = (double) agent.getCurrentTickets() / agent.getMaxTickets();
        int teamSize = agentRepository.findByAgentGroup(agent.getAgentGroup()).size();
        double threshold = getWarningThreshold(agent.getAgentGroup(), teamSize);
        log.debug("客服 {} 负载比率: {:.2f}, 阈值: {:.2f}, 团队规模: {}",
                agent.getAgentId(), usageRatio, threshold, teamSize);
        return usageRatio >= threshold;
    }

    public List<Agent> checkAndWarnHighLoadAgents() {
        if (!properties.getLoadWarning().isEnabled()) {
            log.debug("负载预警功能已禁用");
            return Collections.emptyList();
        }

        List<Agent> allAgents = agentRepository.findAll();
        List<Agent> overloadedAgents = new ArrayList<>();

        for (Agent agent : allAgents) {
            if (!"online".equalsIgnoreCase(agent.getAgentStatus())) {
                continue;
            }

            if (isLoadWarningTriggered(agent)) {
                boolean alreadyWarned = !warnedAgents.add(agent.getAgentId());
                if (!alreadyWarned) {
                    eventPublisher.publishEvent(new LoadWarningEvent(agent,
                            (double) agent.getCurrentTickets() / agent.getMaxTickets()));
                    log.warn("客服 {} 负载已达预警阈值: {}/{} (团队: {})",
                            agent.getAgentId(), agent.getCurrentTickets(), agent.getMaxTickets(), agent.getAgentGroup());
                }
                overloadedAgents.add(agent);
            } else {
                warnedAgents.remove(agent.getAgentId());
            }
        }
        return overloadedAgents;
    }

    public void clearWarning(String agentId) {
        warnedAgents.remove(agentId);
    }

    public void clearAllWarnings() {
        warnedAgents.clear();
    }

    public boolean isAgentWarned(String agentId) {
        return warnedAgents.contains(agentId);
    }

    public int getWarnedAgentsCount() {
        return warnedAgents.size();
    }

    public Map<String, Double> getTeamThresholds(String group, int teamSize) {
        Map<String, Double> thresholds = new HashMap<>();
        thresholds.put("current", getWarningThreshold(group, teamSize));
        thresholds.put("smallTeam", properties.getLoadWarning().getSmallTeamWarningThreshold());
        thresholds.put("mediumTeam", properties.getLoadWarning().getMediumTeamWarningThreshold());
        thresholds.put("largeTeam", properties.getLoadWarning().getLargeTeamWarningThreshold());
        return thresholds;
    }

    public static class LoadWarningEvent {
        private final Agent agent;
        private final double loadRatio;
        private final Date timestamp;

        public LoadWarningEvent(Agent agent, double loadRatio) {
            this.agent = agent;
            this.loadRatio = loadRatio;
            this.timestamp = new Date();
        }

        public Agent getAgent() { return agent; }
        public double getLoadRatio() { return loadRatio; }
        public Date getTimestamp() { return timestamp; }
    }
}
