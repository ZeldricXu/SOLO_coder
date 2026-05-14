package com.servicedesk.service;

import com.servicedesk.entity.*;
import com.servicedesk.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final TicketRepository ticketRepository;
    private final StatusLogRepository statusLogRepository;
    private final ResponseRecordRepository responseRecordRepository;
    private final TransferRecordRepository transferRecordRepository;
    private final SatisfactionRepository satisfactionRepository;

    public Map<String, Object> getTicketFullHistory(String ticketId) {
        Map<String, Object> history = new LinkedHashMap<>();

        Optional<Ticket> ticketOpt = ticketRepository.findById(ticketId);
        if (ticketOpt.isEmpty()) {
            throw new IllegalArgumentException("工单不存在: " + ticketId);
        }

        Ticket ticket = ticketOpt.get();
        history.put("ticket", ticket);

        List<StatusLog> statusLogs = statusLogRepository.findByTicketIdOrderByChangedAtAsc(ticketId);
        history.put("status_history", statusLogs);

        List<ResponseRecord> responses = responseRecordRepository.findByTicketIdOrderByResponseTimeAsc(ticketId);
        history.put("responses", responses);

        List<TransferRecord> transfers = transferRecordRepository.findByTicketIdOrderByTransferTimeAsc(ticketId);
        history.put("transfers", transfers);

        Optional<Satisfaction> satisfaction = satisfactionRepository.findByTicketId(ticketId);
        satisfaction.ifPresent(s -> history.put("satisfaction", s));

        log.info("已获取工单 {} 的完整历史记录", ticketId);
        return history;
    }

    public List<Ticket> getCustomerTicketHistory(String customerId) {
        List<Ticket> tickets = ticketRepository.findByCustomerId(customerId);
        log.info("已获取客户 {} 的 {} 个工单历史", customerId, tickets.size());
        return tickets;
    }

    public List<Ticket> getAgentTicketHistory(String agentId) {
        List<Ticket> tickets = ticketRepository.findByAssigneeId(agentId);
        log.info("已获取客服 {} 的 {} 个工单历史", agentId, tickets.size());
        return tickets;
    }

    public List<StatusLog> getStatusHistory(String ticketId) {
        return statusLogRepository.findByTicketIdOrderByChangedAtAsc(ticketId);
    }

    public List<ResponseRecord> getResponseHistory(String ticketId) {
        return responseRecordRepository.findByTicketIdOrderByResponseTimeAsc(ticketId);
    }

    public List<TransferRecord> getTransferHistory(String ticketId) {
        return transferRecordRepository.findByTicketIdOrderByTransferTimeAsc(ticketId);
    }
}
