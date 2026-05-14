package com.eventticket.service;

import com.eventticket.config.VerificationRetryConfig;
import com.eventticket.dto.TicketVerifyRequest;
import com.eventticket.dto.TicketVerifyResponse;
import com.eventticket.entity.*;
import com.eventticket.repository.*;
import com.eventticket.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
public class VerificationService {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private VerificationRepository verificationRepository;

    @Autowired
    private TicketHistoryService ticketHistoryService;

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private VerificationQueueService queueService;

    @Autowired
    private VerificationRetryConfig retryConfig;

    @Transactional
    public TicketVerifyResponse verifyTicket(TicketVerifyRequest request) {
        String ticketId = request.getTicketId();
        Ticket ticket = ticketRepository.findById(ticketId).orElse(null);

        TicketVerifyResponse response = new TicketVerifyResponse();
        response.setTicketId(ticketId);

        if (ticket == null) {
            createVerificationRecord(ticketId, "invalid", request.getOperator());
            response.setVerifyResult("invalid");
            return response;
        }

        if ("cancelled".equals(ticket.getTicketStatus())) {
            createVerificationRecord(ticketId, "cancelled", request.getOperator());
            response.setVerifyResult("cancelled");
            ticketHistoryService.recordVerification(ticketId, "验证失败：票务已取消", request.getOperator());
            return response;
        }

        if ("used".equals(ticket.getTicketStatus())) {
            createVerificationRecord(ticketId, "already_used", request.getOperator());
            response.setVerifyResult("already_used");
            ticketHistoryService.recordVerification(ticketId, "验证失败：票务已使用", request.getOperator());
            return response;
        }

        if (!"confirmed".equals(ticket.getTicketStatus())) {
            createVerificationRecord(ticketId, "invalid_status", request.getOperator());
            response.setVerifyResult("invalid_status");
            ticketHistoryService.recordVerification(ticketId, "验证失败：票务状态无效", request.getOperator());
            return response;
        }

        Event event = eventRepository.findById(ticket.getEventId()).orElse(null);
        Seat seat = seatRepository.findById(ticket.getSeatId()).orElse(null);

        response.setVerifyResult("valid");
        if (event != null) {
            response.setEventName(event.getEventName());
        }
        if (seat != null) {
            response.setSeatNumber(seat.getSeatNumber());
        }
        response.setParticipantName(ticket.getParticipantName());

        createVerificationRecord(ticketId, "valid", request.getOperator());
        ticketHistoryService.recordVerification(ticketId, "验证通过，准备入场确认", request.getOperator());
        analyticsService.updateMonthlyStatistics();

        queueService.enqueueConfirmationTask(
            ticketId, 
            ticket.getEventId(), 
            ticket.getSeatId(), 
            request.getOperator()
        );

        log.info("Verification passed, confirmation task queued: ticketId={}, eventId={}", 
                ticketId, ticket.getEventId());

        return response;
    }

    private void createVerificationRecord(String ticketId, String result, String operator) {
        Verification verification = new Verification();
        verification.setVerifyId(IdGenerator.generateVerifyId());
        verification.setTicketId(ticketId);
        verification.setVerifyTime(LocalDateTime.now());
        verification.setVerifyResult(result);
        verification.setVerifyOperator(operator);
        verificationRepository.save(verification);
    }

    @Transactional(readOnly = true)
    public Optional<Verification> getVerificationById(String verifyId) {
        return verificationRepository.findById(verifyId);
    }

    @Transactional(readOnly = true)
    public java.util.List<Verification> getVerificationsByTicketId(String ticketId) {
        return verificationRepository.findByTicketId(ticketId);
    }

    public int getRetryCountForEvent(int eventCapacity) {
        return retryConfig.getMaxRetries(eventCapacity);
    }

    public long getQueueSize() {
        return queueService.getQueueSize();
    }

    public long getDeadLetterQueueSize() {
        return queueService.getDeadLetterQueueSize();
    }
}
