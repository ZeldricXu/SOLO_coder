package com.eventticket.service;

import com.eventticket.entity.TicketHistory;
import com.eventticket.repository.TicketHistoryRepository;
import com.eventticket.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TicketHistoryService {

    @Autowired
    private TicketHistoryRepository ticketHistoryRepository;

    @Transactional
    public void recordHistory(String ticketId, String actionType, String description, String operator) {
        TicketHistory history = new TicketHistory();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setTicketId(ticketId);
        history.setActionType(actionType);
        history.setActionTime(LocalDateTime.now());
        history.setActionDescription(description);
        history.setOperator(operator);
        ticketHistoryRepository.save(history);
    }

    @Transactional(readOnly = true)
    public List<TicketHistory> getTicketHistory(String ticketId) {
        return ticketHistoryRepository.findFullHistoryByTicketId(ticketId);
    }

    public void recordBooking(String ticketId, String description) {
        recordHistory(ticketId, "BOOKING", description, "SYSTEM");
    }

    public void recordPayment(String ticketId, String description) {
        recordHistory(ticketId, "PAYMENT", description, "SYSTEM");
    }

    public void recordVerification(String ticketId, String description, String operator) {
        recordHistory(ticketId, "VERIFICATION", description, operator);
    }

    public void recordRefund(String ticketId, String description) {
        recordHistory(ticketId, "REFUND", description, "SYSTEM");
    }

    public void recordChange(String ticketId, String description) {
        recordHistory(ticketId, "CHANGE", description, "SYSTEM");
    }

    public void recordCancellation(String ticketId, String description) {
        recordHistory(ticketId, "CANCELLATION", description, "SYSTEM");
    }
}
