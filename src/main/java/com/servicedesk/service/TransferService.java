package com.servicedesk.service;

import com.servicedesk.dto.TransferTicketRequest;
import com.servicedesk.entity.Agent;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TransferRecord;
import com.servicedesk.repository.AgentRepository;
import com.servicedesk.repository.TicketRepository;
import com.servicedesk.repository.TransferRecordRepository;
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
public class TransferService {

    private final TransferRecordRepository transferRecordRepository;
    private final TicketRepository ticketRepository;
    private final AgentRepository agentRepository;
    private final AssignmentService assignmentService;
    private final StatusTrackingService statusTrackingService;

    @Transactional
    public TransferRecord transferTicket(TransferTicketRequest request) {
        Optional<Ticket> ticketOpt = ticketRepository.findById(request.getTicketId());
        if (ticketOpt.isEmpty()) {
            throw new IllegalArgumentException("工单不存在: " + request.getTicketId());
        }

        Ticket ticket = ticketOpt.get();
        String fromAgentId = request.getFromAgentId() != null ? request.getFromAgentId() : ticket.getAssigneeId();

        if (fromAgentId == null) {
            throw new IllegalStateException("工单尚未分配，无法转派");
        }

        Optional<Agent> toAgentOpt = agentRepository.findById(request.getToAgentId());
        if (toAgentOpt.isEmpty()) {
            throw new IllegalArgumentException("目标客服不存在: " + request.getToAgentId());
        }

        Agent toAgent = toAgentOpt.get();
        if (!toAgent.canAcceptTicket()) {
            throw new IllegalStateException("目标客服 " + request.getToAgentId() + " 无法接受更多工单");
        }

        String currentStatus = ticket.getTicketStatus();
        if (StatusTrackingService.STATUS_RESOLVED.equals(currentStatus) ||
            StatusTrackingService.STATUS_CLOSED.equals(currentStatus)) {
            throw new IllegalStateException("工单已完成，无法转派");
        }

        TransferRecord transferRecord = new TransferRecord();
        transferRecord.setTransferId("transfer_" + UUID.randomUUID().toString().substring(0, 8));
        transferRecord.setTicketId(request.getTicketId());
        transferRecord.setFromAgent(fromAgentId);
        transferRecord.setToAgent(request.getToAgentId());
        transferRecord.setTransferReason(request.getTransferReason());
        transferRecord.setTransferTime(Instant.now());

        TransferRecord savedRecord = transferRecordRepository.save(transferRecord);

        Optional<Agent> fromAgentOpt = agentRepository.findById(fromAgentId);
        fromAgentOpt.ifPresent(assignmentService::decrementAgentTicketCount);

        assignmentService.incrementAgentTicketCount(toAgent);

        String oldStatus = ticket.getTicketStatus();
        ticket.setAssigneeId(request.getToAgentId());
        ticket.setAssignedAt(Instant.now());
        ticket.setTicketStatus(StatusTrackingService.STATUS_TRANSFERRED);
        ticketRepository.save(ticket);

        statusTrackingService.logStatusChange(
                request.getTicketId(),
                oldStatus,
                StatusTrackingService.STATUS_TRANSFERRED,
                fromAgentId
        );

        ticket.setTicketStatus(StatusTrackingService.STATUS_ASSIGNED);
        ticketRepository.save(ticket);

        statusTrackingService.logStatusChange(
                request.getTicketId(),
                StatusTrackingService.STATUS_TRANSFERRED,
                StatusTrackingService.STATUS_ASSIGNED,
                "system"
        );

        log.info("工单 {} 已从客服 {} 转派给客服 {}", request.getTicketId(), fromAgentId, request.getToAgentId());
        return savedRecord;
    }

    public List<TransferRecord> getTransferHistory(String ticketId) {
        return transferRecordRepository.findByTicketIdOrderByTransferTimeAsc(ticketId);
    }

    public Optional<TransferRecord> getTransferById(String transferId) {
        return transferRecordRepository.findById(transferId);
    }
}
