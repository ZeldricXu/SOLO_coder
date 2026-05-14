package com.servicedesk.service;

import com.servicedesk.config.ServiceDeskProperties;
import com.servicedesk.entity.Ticket;
import com.servicedesk.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResponseTimeoutService {

    private final TicketRepository ticketRepository;
    private final ServiceDeskProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    private final Set<String> warnedTickets = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> timedOutTickets = Collections.synchronizedSet(new HashSet<>());

    public int getTimeoutByPriority(String priority) {
        return properties.getResponseTimeout().getTimeoutByUrgency(priority);
    }

    public int getWarningThresholdByPriority(String priority) {
        return properties.getResponseTimeout().getWarningThresholdByUrgency(priority);
    }

    public ServiceDeskProperties.ResponseTimeoutConfig.UrgencyConfig getUrgencyConfig(String priority) {
        return properties.getResponseTimeout().getUrgencyConfig(priority);
    }

    public long getElapsedSeconds(Ticket ticket) {
        if (ticket.getAssignedAt() == null) {
            return 0;
        }
        return Duration.between(ticket.getAssignedAt(), Instant.now()).getSeconds();
    }

    public boolean isResponseWarningTriggered(Ticket ticket) {
        if (!properties.getResponseTimeout().isEnabled()) {
            return false;
        }
        if (ticket.getAssignedAt() == null) {
            return false;
        }
        String currentStatus = ticket.getTicketStatus();
        if (StatusTrackingService.STATUS_IN_PROGRESS.equals(currentStatus) ||
            StatusTrackingService.STATUS_RESOLVED.equals(currentStatus) ||
            StatusTrackingService.STATUS_CLOSED.equals(currentStatus)) {
            return false;
        }
        long elapsed = getElapsedSeconds(ticket);
        int warningThreshold = getWarningThresholdByPriority(ticket.getTicketPriority());
        log.debug("工单 {} 已消耗时间: {}秒, 警告阈值: {}秒", ticket.getTicketId(), elapsed, warningThreshold);
        return elapsed >= warningThreshold;
    }

    public boolean isResponseTimedOut(Ticket ticket) {
        if (!properties.getResponseTimeout().isEnabled()) {
            return false;
        }
        if (ticket.getAssignedAt() == null) {
            return false;
        }
        String currentStatus = ticket.getTicketStatus();
        if (StatusTrackingService.STATUS_IN_PROGRESS.equals(currentStatus) ||
            StatusTrackingService.STATUS_RESOLVED.equals(currentStatus) ||
            StatusTrackingService.STATUS_CLOSED.equals(currentStatus)) {
            return false;
        }
        long elapsed = getElapsedSeconds(ticket);
        int timeout = getTimeoutByPriority(ticket.getTicketPriority());
        return elapsed >= timeout;
    }

    public List<Ticket> checkAndWarnTimeoutTickets() {
        if (!properties.getResponseTimeout().isEnabled()) {
            log.debug("响应超时功能已禁用");
            return Collections.emptyList();
        }

        List<Ticket> pendingTickets = ticketRepository.findByTicketStatus(StatusTrackingService.STATUS_ASSIGNED);
        List<Ticket> warningTickets = new ArrayList<>();

        for (Ticket ticket : pendingTickets) {
            if (isResponseTimedOut(ticket)) {
                boolean alreadyTimedOut = !timedOutTickets.add(ticket.getTicketId());
                if (!alreadyTimedOut) {
                    eventPublisher.publishEvent(new ResponseTimeoutEvent(
                            ticket, ResponseTimeoutEvent.Type.TIMEOUT,
                            getElapsedSeconds(ticket),
                            getTimeoutByPriority(ticket.getTicketPriority())
                    ));
                    log.error("工单 {} 响应已超时! 耗时: {}秒, 优先级: {}",
                            ticket.getTicketId(), getElapsedSeconds(ticket), ticket.getTicketPriority());
                }
                warningTickets.add(ticket);
            } else if (isResponseWarningTriggered(ticket)) {
                boolean alreadyWarned = !warnedTickets.add(ticket.getTicketId());
                if (!alreadyWarned) {
                    eventPublisher.publishEvent(new ResponseTimeoutEvent(
                            ticket, ResponseTimeoutEvent.Type.WARNING,
                            getElapsedSeconds(ticket),
                            getWarningThresholdByPriority(ticket.getTicketPriority())
                    ));
                    log.warn("工单 {} 响应即将超时! 耗时: {}秒, 警告阈值: {}秒",
                            ticket.getTicketId(), getElapsedSeconds(ticket),
                            getWarningThresholdByPriority(ticket.getTicketPriority()));
                }
                warningTickets.add(ticket);
            }
        }
        return warningTickets;
    }

    public void markAsResponded(String ticketId) {
        warnedTickets.remove(ticketId);
        timedOutTickets.remove(ticketId);
    }

    public void clearAllWarnings() {
        warnedTickets.clear();
        timedOutTickets.clear();
    }

    public boolean isTicketWarned(String ticketId) {
        return warnedTickets.contains(ticketId);
    }

    public boolean isTicketTimedOut(String ticketId) {
        return timedOutTickets.contains(ticketId);
    }

    public int getWarnedTicketsCount() {
        return warnedTickets.size();
    }

    public int getTimedOutTicketsCount() {
        return timedOutTickets.size();
    }

    public Map<String, Integer> getPriorityTimeouts() {
        Map<String, Integer> timeouts = new HashMap<>();
        timeouts.put("high", getTimeoutByPriority("high"));
        timeouts.put("medium", getTimeoutByPriority("medium"));
        timeouts.put("low", getTimeoutByPriority("low"));
        timeouts.put("default", properties.getResponseTimeout().getDefaultTimeoutSeconds());
        return timeouts;
    }

    public void updateUrgencyConfig(String urgency, int timeoutSeconds, String alertMessage) {
        properties.getResponseTimeout().addUrgencyConfig(urgency, timeoutSeconds, alertMessage);
        log.info("已更新紧急程度配置: {} -> {}秒", urgency, timeoutSeconds);
    }

    public static class ResponseTimeoutEvent {
        public enum Type { WARNING, TIMEOUT }

        private final Ticket ticket;
        private final Type type;
        private final long elapsedSeconds;
        private final int thresholdSeconds;
        private final Date timestamp;

        public ResponseTimeoutEvent(Ticket ticket, Type type, long elapsedSeconds, int thresholdSeconds) {
            this.ticket = ticket;
            this.type = type;
            this.elapsedSeconds = elapsedSeconds;
            this.thresholdSeconds = thresholdSeconds;
            this.timestamp = new Date();
        }

        public Ticket getTicket() { return ticket; }
        public Type getType() { return type; }
        public long getElapsedSeconds() { return elapsedSeconds; }
        public int getThresholdSeconds() { return thresholdSeconds; }
        public Date getTimestamp() { return timestamp; }
    }
}
