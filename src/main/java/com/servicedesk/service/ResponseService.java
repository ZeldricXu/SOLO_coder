package com.servicedesk.service;

import com.servicedesk.dto.TicketResponseRequest;
import com.servicedesk.entity.ResponseRecord;
import com.servicedesk.entity.Ticket;
import com.servicedesk.repository.ResponseRecordRepository;
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
public class ResponseService {

    private final ResponseRecordRepository responseRecordRepository;
    private final TicketRepository ticketRepository;
    private final StatusTrackingService statusTrackingService;
    private final StatisticsService statisticsService;

    @Transactional
    public ResponseRecord recordResponse(TicketResponseRequest request) {
        Optional<Ticket> ticketOpt = ticketRepository.findById(request.getTicketId());
        if (ticketOpt.isEmpty()) {
            throw new IllegalArgumentException("工单不存在: " + request.getTicketId());
        }

        Ticket ticket = ticketOpt.get();
        String currentStatus = ticket.getTicketStatus();

        if (StatusTrackingService.STATUS_RESOLVED.equals(currentStatus) || 
            StatusTrackingService.STATUS_CLOSED.equals(currentStatus)) {
            throw new IllegalStateException("工单已完成，无法响应");
        }

        String agentId = request.getAgentId();
        if (agentId == null || agentId.isEmpty()) {
            agentId = ticket.getAssigneeId();
        }

        if (agentId != null && ticket.getAssigneeId() != null && !agentId.equals(ticket.getAssigneeId())) {
            log.warn("客服 {} 试图响应分配给客服 {} 的工单", agentId, ticket.getAssigneeId());
        }

        String responseType = request.getResponseType() != null ? request.getResponseType() : "reply";

        ResponseRecord responseRecord = new ResponseRecord();
        responseRecord.setResponseId("response_" + UUID.randomUUID().toString().substring(0, 8));
        responseRecord.setTicketId(request.getTicketId());
        responseRecord.setAgentId(agentId);
        responseRecord.setResponseContent(request.getResponseContent());
        responseRecord.setResponseTime(Instant.now());
        responseRecord.setResponseType(responseType);

        ResponseRecord savedRecord = responseRecordRepository.save(responseRecord);
        log.info("响应记录已保存: {}", savedRecord.getResponseId());

        boolean isFirstResponse = !responseRecordRepository.existsByTicketId(request.getTicketId()) ||
                responseRecordRepository.countByTicketId(request.getTicketId()) <= 1;

        if (isFirstResponse && ticket.getCreatedAt() != null) {
            long responseTime = java.time.Duration.between(ticket.getCreatedAt(), Instant.now()).getSeconds();
            ticket.setFirstResponseAt(Instant.now());
            ticket.setResponseTimeSeconds(responseTime);
            statisticsService.updateResponseTimeStats(responseTime);
            log.info("首次响应时间: {} 秒", responseTime);
        }

        if (StatusTrackingService.STATUS_ASSIGNED.equals(currentStatus)) {
            ticket.setTicketStatus(StatusTrackingService.STATUS_IN_PROGRESS);
            statusTrackingService.logStatusChange(
                    request.getTicketId(),
                    StatusTrackingService.STATUS_ASSIGNED,
                    StatusTrackingService.STATUS_IN_PROGRESS,
                    agentId != null ? agentId : "system"
            );
        }

        ticketRepository.save(ticket);

        if (Boolean.TRUE.equals(request.getMarkResolved())) {
            resolveTicket(request.getTicketId(), agentId);
        }

        return savedRecord;
    }

    @Transactional
    public Ticket resolveTicket(String ticketId, String agentId) {
        Optional<Ticket> ticketOpt = ticketRepository.findById(ticketId);
        if (ticketOpt.isEmpty()) {
            throw new IllegalArgumentException("工单不存在: " + ticketId);
        }

        Ticket ticket = ticketOpt.get();
        String currentStatus = ticket.getTicketStatus();

        if (StatusTrackingService.STATUS_RESOLVED.equals(currentStatus) ||
            StatusTrackingService.STATUS_CLOSED.equals(currentStatus)) {
            return ticket;
        }

        Instant now = Instant.now();
        ticket.setResolvedAt(now);

        if (ticket.getCreatedAt() != null) {
            long resolutionTime = java.time.Duration.between(ticket.getCreatedAt(), now).getSeconds();
            ticket.setResolutionTimeSeconds(resolutionTime);
            statisticsService.updateResolutionTimeStats(resolutionTime);
            log.info("工单解决时间: {} 秒", resolutionTime);
        }

        String oldStatus = ticket.getTicketStatus();
        ticket.setTicketStatus(StatusTrackingService.STATUS_RESOLVED);
        Ticket resolvedTicket = ticketRepository.save(ticket);

        statusTrackingService.logStatusChange(
                ticketId,
                oldStatus,
                StatusTrackingService.STATUS_RESOLVED,
                agentId != null ? agentId : "system"
        );

        statisticsService.incrementResolvedTickets();
        log.info("工单 {} 已解决", ticketId);

        return resolvedTicket;
    }

    public List<ResponseRecord> getResponsesByTicketId(String ticketId) {
        return responseRecordRepository.findByTicketIdOrderByResponseTimeAsc(ticketId);
    }

    public Optional<ResponseRecord> getResponseById(String responseId) {
        return responseRecordRepository.findById(responseId);
    }
}
