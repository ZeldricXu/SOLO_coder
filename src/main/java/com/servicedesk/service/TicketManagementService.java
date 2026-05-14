package com.servicedesk.service;

import com.servicedesk.dto.CreateTicketRequest;
import com.servicedesk.entity.Agent;
import com.servicedesk.entity.Ticket;
import com.servicedesk.repository.AgentRepository;
import com.servicedesk.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketManagementService {

    private final TicketRepository ticketRepository;
    private final AgentRepository agentRepository;
    private final PriorityService priorityService;
    private final AssignmentService assignmentService;
    private final StatusTrackingService statusTrackingService;
    private final StatisticsService statisticsService;

    @Transactional
    public Ticket createTicket(CreateTicketRequest request) {
        String ticketId = "ticket_" + UUID.randomUUID().toString().substring(0, 8);

        String category = priorityService.determineCategory(request.getTicketCategory());
        String priority = priorityService.evaluatePriority(request);
        String customerId = request.getCustomerId() != null ? request.getCustomerId() : "customer_" + UUID.randomUUID().toString().substring(0, 8);

        Ticket ticket = new Ticket();
        ticket.setTicketId(ticketId);
        ticket.setTicketTitle(request.getTicketTitle());
        ticket.setTicketContent(request.getTicketContent());
        ticket.setTicketCategory(category);
        ticket.setTicketPriority(priority);
        ticket.setTicketStatus(StatusTrackingService.STATUS_CREATED);
        ticket.setCustomerId(customerId);
        ticket.setCreatedAt(Instant.now());

        Ticket savedTicket = ticketRepository.save(ticket);

        statusTrackingService.logStatusChange(
                ticketId,
                StatusTrackingService.STATUS_CREATED,
                StatusTrackingService.STATUS_CREATED,
                "system"
        );

        log.info("工单已创建: {}", ticketId);

        Ticket assignedTicket = assignmentService.autoAssignTicket(savedTicket);

        statisticsService.incrementTotalTickets();

        log.info("工单 {} 状态: {}", ticketId, assignedTicket.getTicketStatus());
        return assignedTicket;
    }

    public Optional<Ticket> getTicketById(String ticketId) {
        return ticketRepository.findById(ticketId);
    }

    public List<Ticket> getTicketsByCustomerId(String customerId) {
        return ticketRepository.findByCustomerId(customerId);
    }

    public List<Ticket> getTicketsByAssigneeId(String assigneeId) {
        return ticketRepository.findByAssigneeId(assigneeId);
    }

    public List<Ticket> getTicketsByStatus(String status) {
        return ticketRepository.findByTicketStatus(status);
    }

    public List<Ticket> getAllTickets() {
        return ticketRepository.findAll();
    }

    public boolean ticketExists(String ticketId) {
        return ticketRepository.existsById(ticketId);
    }

    @Transactional
    public Ticket updateTicket(Ticket ticket) {
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket resolveTicket(String ticketId) {
        Optional<Ticket> ticketOpt = ticketRepository.findById(ticketId);
        if (ticketOpt.isEmpty()) {
            throw new IllegalArgumentException("工单不存在: " + ticketId);
        }

        Ticket ticket = ticketOpt.get();
        String oldStatus = ticket.getTicketStatus();
        Instant now = Instant.now();
        ticket.setResolvedAt(now);

        if (ticket.getCreatedAt() != null) {
            long resolutionTime = java.time.Duration.between(ticket.getCreatedAt(), now).getSeconds();
            ticket.setResolutionTimeSeconds(resolutionTime);
            statisticsService.updateResolutionTimeStats(resolutionTime);
        }

        ticket.setTicketStatus(StatusTrackingService.STATUS_RESOLVED);
        Ticket resolvedTicket = ticketRepository.save(ticket);

        statusTrackingService.logStatusChange(
                ticketId,
                oldStatus,
                StatusTrackingService.STATUS_RESOLVED,
                ticket.getAssigneeId() != null ? ticket.getAssigneeId() : "system"
        );

        if (ticket.getAssigneeId() != null) {
            Optional<Agent> agentOpt = agentRepository.findById(ticket.getAssigneeId());
            agentOpt.ifPresent(assignmentService::decrementAgentTicketCount);
        }

        statisticsService.incrementResolvedTickets();
        log.info("工单 {} 已解决", ticketId);
        return resolvedTicket;
    }
}
