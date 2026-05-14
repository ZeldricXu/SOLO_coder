package com.servicedesk.service;

import com.servicedesk.entity.StatusLog;
import com.servicedesk.entity.Ticket;
import com.servicedesk.repository.StatusLogRepository;
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
public class StatusTrackingService {

    private final StatusLogRepository statusLogRepository;
    private final TicketRepository ticketRepository;

    public static final String STATUS_CREATED = "created";
    public static final String STATUS_PENDING_ASSIGNMENT = "pending_assignment";
    public static final String STATUS_ASSIGNED = "assigned";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_TRANSFERRED = "transferred";
    public static final String STATUS_RESOLVED = "resolved";
    public static final String STATUS_CLOSED = "closed";

    @Transactional
    public StatusLog logStatusChange(String ticketId, String fromStatus, String toStatus, String changedBy) {
        StatusLog statusLog = new StatusLog();
        statusLog.setStatusLogId("log_" + UUID.randomUUID().toString().substring(0, 8));
        statusLog.setTicketId(ticketId);
        statusLog.setFromStatus(fromStatus);
        statusLog.setToStatus(toStatus);
        statusLog.setChangedAt(Instant.now());
        statusLog.setChangedBy(changedBy);

        StatusLog savedLog = statusLogRepository.save(statusLog);
        log.info("状态变更记录已保存: {} -> {}", fromStatus, toStatus);
        return savedLog;
    }

    @Transactional
    public Ticket updateTicketStatus(String ticketId, String newStatus, String changedBy) {
        Optional<Ticket> ticketOpt = ticketRepository.findById(ticketId);
        if (ticketOpt.isEmpty()) {
            throw new IllegalArgumentException("工单不存在: " + ticketId);
        }

        Ticket ticket = ticketOpt.get();
        String oldStatus = ticket.getTicketStatus();

        if (!oldStatus.equals(newStatus)) {
            ticket.setTicketStatus(newStatus);
            Ticket updatedTicket = ticketRepository.save(ticket);
            logStatusChange(ticketId, oldStatus, newStatus, changedBy);
            log.info("工单 {} 状态已更新: {} -> {}", ticketId, oldStatus, newStatus);
            return updatedTicket;
        }

        return ticket;
    }

    public List<StatusLog> getStatusHistory(String ticketId) {
        return statusLogRepository.findByTicketIdOrderByChangedAtAsc(ticketId);
    }

    public boolean isValidStatusTransition(String fromStatus, String toStatus) {
        if (fromStatus == null || toStatus == null) {
            return false;
        }

        switch (fromStatus) {
            case STATUS_CREATED:
                return toStatus.equals(STATUS_PENDING_ASSIGNMENT) || toStatus.equals(STATUS_ASSIGNED);
            case STATUS_PENDING_ASSIGNMENT:
                return toStatus.equals(STATUS_ASSIGNED);
            case STATUS_ASSIGNED:
                return toStatus.equals(STATUS_IN_PROGRESS) || toStatus.equals(STATUS_TRANSFERRED);
            case STATUS_IN_PROGRESS:
                return toStatus.equals(STATUS_TRANSFERRED) || toStatus.equals(STATUS_RESOLVED);
            case STATUS_TRANSFERRED:
                return toStatus.equals(STATUS_ASSIGNED) || toStatus.equals(STATUS_IN_PROGRESS);
            case STATUS_RESOLVED:
                return toStatus.equals(STATUS_CLOSED);
            case STATUS_CLOSED:
                return false;
            default:
                return true;
        }
    }
}
