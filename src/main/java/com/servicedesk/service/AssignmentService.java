package com.servicedesk.service;

import com.servicedesk.config.ServiceDeskProperties;
import com.servicedesk.entity.Agent;
import com.servicedesk.entity.Ticket;
import com.servicedesk.repository.AgentRepository;
import com.servicedesk.repository.TicketRepository;
import com.servicedesk.strategy.AssignmentStrategy;
import com.servicedesk.strategy.StrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssignmentService {

    private final AgentRepository agentRepository;
    private final TicketRepository ticketRepository;
    private final StatusTrackingService statusTrackingService;
    private final StrategyFactory strategyFactory;
    private final ServiceDeskProperties properties;

    public Optional<Agent> findBestAgent(Ticket ticket) {
        String ticketCategory = ticket.getTicketCategory();
        String agentGroup = mapCategoryToGroup(ticketCategory);

        List<Agent> availableAgents = agentRepository.findAvailableAgentsByGroup(agentGroup);

        if (availableAgents.isEmpty()) {
            availableAgents = agentRepository.findAllAvailableAgents();
        }

        if (availableAgents.isEmpty()) {
            log.warn("没有可用的客服人员，工单 {} 将进入待分配队列", ticket.getTicketId());
            return Optional.empty();
        }

        AssignmentStrategy strategy = strategyFactory.getStrategyForTicket(ticketCategory, agentGroup);
        log.debug("使用分配策略: {} 处理工单: {}", strategy.getName(), ticket.getTicketId());

        Map<String, Object> strategyParams = properties.getAssignmentStrategy()
                .getStrategyConfig(strategy.getName()) != null ?
                properties.getAssignmentStrategy().getStrategyConfig(strategy.getName()).getParameters() :
                null;

        Agent selectedAgent = strategy.selectAgent(ticket, availableAgents, strategyParams);

        if (selectedAgent == null && !availableAgents.isEmpty()) {
            selectedAgent = availableAgents.get(0);
        }

        if (selectedAgent != null) {
            log.info("为工单 {} 选择客服: {} (策略: {})",
                    ticket.getTicketId(), selectedAgent.getAgentId(), strategy.getName());
            return Optional.of(selectedAgent);
        }

        return Optional.empty();
    }

    private String mapCategoryToGroup(String category) {
        if (category == null) {
            return "general_support";
        }
        switch (category.toLowerCase()) {
            case "technical":
                return "technical_support";
            case "billing":
                return "billing_support";
            case "业务":
            case "业务咨询":
                return "business_support";
            default:
                return "general_support";
        }
    }

    public String getCurrentStrategyName(String category, String group) {
        return strategyFactory.getStrategyForTicket(category, group).getName();
    }

    public void switchStrategy(String strategyName) {
        if (properties.getAssignmentStrategy().isStrategyEnabled(strategyName)) {
            properties.getAssignmentStrategy().setDefaultStrategy(strategyName);
            log.info("默认分配策略已切换为: {}", strategyName);
        } else {
            log.warn("策略 {} 不可用或已禁用", strategyName);
        }
    }

    @Transactional
    public Ticket autoAssignTicket(Ticket ticket) {
        Optional<Agent> agentOpt = findBestAgent(ticket);

        if (agentOpt.isPresent()) {
            return assignTicketToAgent(ticket, agentOpt.get());
        } else {
            ticket.setTicketStatus(StatusTrackingService.STATUS_PENDING_ASSIGNMENT);
            Ticket savedTicket = ticketRepository.save(ticket);
            statusTrackingService.logStatusChange(
                    ticket.getTicketId(),
                    StatusTrackingService.STATUS_CREATED,
                    StatusTrackingService.STATUS_PENDING_ASSIGNMENT,
                    "system"
            );
            log.info("工单 {} 已进入待分配队列", ticket.getTicketId());
            return savedTicket;
        }
    }

    @Transactional
    public Ticket manualAssignTicket(String ticketId, String agentId) {
        Optional<Ticket> ticketOpt = ticketRepository.findById(ticketId);
        Optional<Agent> agentOpt = agentRepository.findById(agentId);

        if (ticketOpt.isEmpty()) {
            throw new IllegalArgumentException("工单不存在: " + ticketId);
        }
        if (agentOpt.isEmpty()) {
            throw new IllegalArgumentException("客服不存在: " + agentId);
        }

        Agent agent = agentOpt.get();
        if (!agent.canAcceptTicket()) {
            throw new IllegalStateException("客服 " + agentId + " 无法接受更多工单");
        }

        Ticket ticket = ticketOpt.get();
        String oldAssignee = ticket.getAssigneeId();

        if (oldAssignee != null && !oldAssignee.equals(agentId)) {
            Optional<Agent> oldAgentOpt = agentRepository.findById(oldAssignee);
            oldAgentOpt.ifPresent(this::decrementAgentTicketCount);
        }

        return assignTicketToAgent(ticket, agent);
    }

    @Transactional
    public Ticket assignTicketToAgent(Ticket ticket, Agent agent) {
        String oldStatus = ticket.getTicketStatus();

        ticket.setAssigneeId(agent.getAgentId());
        ticket.setAssignedAt(Instant.now());
        ticket.setTicketStatus(StatusTrackingService.STATUS_ASSIGNED);

        Ticket savedTicket = ticketRepository.save(ticket);

        incrementAgentTicketCount(agent);

        if (!StatusTrackingService.STATUS_ASSIGNED.equals(oldStatus)) {
            statusTrackingService.logStatusChange(
                    ticket.getTicketId(),
                    oldStatus,
                    StatusTrackingService.STATUS_ASSIGNED,
                    "system"
            );
        }

        log.info("工单 {} 已分配给客服 {}", ticket.getTicketId(), agent.getAgentId());
        return savedTicket;
    }

    @Transactional
    public void incrementAgentTicketCount(Agent agent) {
        agent.setCurrentTickets(agent.getCurrentTickets() + 1);
        agentRepository.save(agent);
    }

    @Transactional
    public void decrementAgentTicketCount(Agent agent) {
        if (agent.getCurrentTickets() > 0) {
            agent.setCurrentTickets(agent.getCurrentTickets() - 1);
            agentRepository.save(agent);
        }
    }

    @Transactional
    public void processPendingTickets() {
        List<Ticket> pendingTickets = ticketRepository.findByTicketStatus(StatusTrackingService.STATUS_PENDING_ASSIGNMENT);

        for (Ticket ticket : pendingTickets) {
            Optional<Agent> agentOpt = findBestAgent(ticket);
            if (agentOpt.isPresent()) {
                log.info("为待分配工单 {} 找到可用客服", ticket.getTicketId());
                assignTicketToAgent(ticket, agentOpt.get());
            }
        }
    }
}
