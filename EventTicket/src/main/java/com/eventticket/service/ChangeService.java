package com.eventticket.service;

import com.eventticket.dto.ChangeRequest;
import com.eventticket.entity.*;
import com.eventticket.repository.*;
import com.eventticket.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class ChangeService {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ChangeRecordRepository changeRecordRepository;

    @Autowired
    private TicketHistoryService ticketHistoryService;

    @Autowired
    private AnalyticsService analyticsService;

    @Transactional
    public ChangeRecord processRefund(ChangeRequest request) {
        Ticket ticket = ticketRepository.findById(request.getTicketId()).orElse(null);
        if (ticket == null) {
            throw new RuntimeException("票务不存在");
        }

        if (!"confirmed".equals(ticket.getTicketStatus())) {
            throw new RuntimeException("只能退改已确认的票务");
        }

        int refundAmount = calculateRefundAmount(ticket);

        ticket.setTicketStatus("refunded");
        ticket.setCancelledAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        Optional<Seat> seatOpt = seatRepository.findById(ticket.getSeatId());
        if (seatOpt.isPresent()) {
            Seat seat = seatOpt.get();
            seat.setSeatStatus("available");
            seat.setSoldAt(null);
            seat.setLockedAt(null);
            seatRepository.save(seat);
        }

        ChangeRecord changeRecord = new ChangeRecord();
        changeRecord.setChangeId(IdGenerator.generateChangeId());
        changeRecord.setTicketId(request.getTicketId());
        changeRecord.setChangeType("refund");
        changeRecord.setChangeReason(request.getChangeReason());
        changeRecord.setChangeAmount(refundAmount);
        changeRecord.setChangeStatus("approved");
        changeRecord.setChangeTime(LocalDateTime.now());
        changeRecordRepository.save(changeRecord);

        ticketHistoryService.recordRefund(request.getTicketId(), "退票成功，退款金额: " + refundAmount);
        analyticsService.updateMonthlyStatistics();

        return changeRecord;
    }

    @Transactional
    public ChangeRecord processExchange(ChangeRequest request) {
        Ticket ticket = ticketRepository.findById(request.getTicketId()).orElse(null);
        if (ticket == null) {
            throw new RuntimeException("票务不存在");
        }

        if (!"confirmed".equals(ticket.getTicketStatus())) {
            throw new RuntimeException("只能改签已确认的票务");
        }

        if (request.getNewSeatId() == null || request.getNewSeatId().isEmpty()) {
            throw new RuntimeException("改签必须指定新座位");
        }

        Seat newSeat = seatRepository.findByIdWithLock(request.getNewSeatId()).orElse(null);
        if (newSeat == null) {
            throw new RuntimeException("新座位不存在");
        }
        if (!"available".equals(newSeat.getSeatStatus())) {
            throw new RuntimeException("新座位不可用");
        }
        if (!ticket.getEventId().equals(newSeat.getEventId())) {
            throw new RuntimeException("只能改签同一活动的座位");
        }

        Optional<Seat> oldSeatOpt = seatRepository.findById(ticket.getSeatId());
        if (oldSeatOpt.isPresent()) {
            Seat oldSeat = oldSeatOpt.get();
            oldSeat.setSeatStatus("available");
            oldSeat.setSoldAt(null);
            oldSeat.setLockedAt(null);
            seatRepository.save(oldSeat);
        }

        newSeat.setSeatStatus("sold");
        newSeat.setSoldAt(LocalDateTime.now());
        seatRepository.save(newSeat);

        ticket.setSeatId(newSeat.getSeatId());
        ticket.setTicketPrice(newSeat.getSeatPrice());
        ticketRepository.save(ticket);

        ChangeRecord changeRecord = new ChangeRecord();
        changeRecord.setChangeId(IdGenerator.generateChangeId());
        changeRecord.setTicketId(request.getTicketId());
        changeRecord.setChangeType("exchange");
        changeRecord.setChangeReason(request.getChangeReason());
        changeRecord.setChangeAmount(newSeat.getSeatPrice());
        changeRecord.setChangeStatus("approved");
        changeRecord.setChangeTime(LocalDateTime.now());
        changeRecordRepository.save(changeRecord);

        ticketHistoryService.recordChange(request.getTicketId(), "改签成功，新座位: " + newSeat.getSeatNumber());
        analyticsService.updateMonthlyStatistics();

        return changeRecord;
    }

    private int calculateRefundAmount(Ticket ticket) {
        LocalDateTime eventDate = null;
        Optional<Event> eventOpt = Optional.empty();
        if (eventOpt.isPresent() && eventOpt.get().getEventDate() != null) {
            eventDate = eventOpt.get().getEventDate();
        }
        if (eventDate != null) {
            LocalDateTime now = LocalDateTime.now();
            if (now.plusDays(7).isBefore(eventDate)) {
                return (int) (ticket.getTicketPrice() * 0.9);
            } else if (now.plusDays(3).isBefore(eventDate)) {
                return (int) (ticket.getTicketPrice() * 0.7);
            }
        }
        return (int) (ticket.getTicketPrice() * 0.5);
    }

    @Transactional(readOnly = true)
    public Optional<ChangeRecord> getChangeRecordById(String changeId) {
        return changeRecordRepository.findById(changeId);
    }

    @Transactional(readOnly = true)
    public java.util.List<ChangeRecord> getChangeRecordsByTicketId(String ticketId) {
        return changeRecordRepository.findByTicketId(ticketId);
    }

    @Transactional(readOnly = true)
    public java.util.List<ChangeRecord> getRefundsByTicketId(String ticketId) {
        return changeRecordRepository.findByTicketIdAndChangeType(ticketId, "refund");
    }

    @Transactional(readOnly = true)
    public java.util.List<ChangeRecord> getExchangesByTicketId(String ticketId) {
        return changeRecordRepository.findByTicketIdAndChangeType(ticketId, "exchange");
    }
}
