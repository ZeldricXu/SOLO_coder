package com.servicedesk.config;

import com.servicedesk.entity.Agent;
import com.servicedesk.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final AgentRepository agentRepository;

    @Override
    public void run(String... args) {
        if (agentRepository.count() == 0) {
            log.info("初始化客服数据...");
            
            List<Agent> agents = Arrays.asList(
                    createAgent("agent_001", "客服人员01", "technical_support"),
                    createAgent("agent_002", "客服人员02", "technical_support"),
                    createAgent("agent_003", "客服人员03", "business_support"),
                    createAgent("agent_004", "客服人员04", "general_support")
            );

            agentRepository.saveAll(agents);
            log.info("已初始化 {} 个客服", agents.size());
        }
    }

    private Agent createAgent(String agentId, String agentName, String agentGroup) {
        Agent agent = new Agent();
        agent.setAgentId(agentId);
        agent.setAgentName(agentName);
        agent.setAgentGroup(agentGroup);
        agent.setAgentStatus("online");
        agent.setCurrentTickets(0);
        agent.setMaxTickets(5);
        agent.setResponseAvgTime(60);
        return agent;
    }
}
