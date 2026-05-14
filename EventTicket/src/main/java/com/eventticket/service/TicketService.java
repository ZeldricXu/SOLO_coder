package com.eventticket.service;

import com.eventticket.config.SeatSectionConfig;
import com.eventticket.config.TicketLockConfig;
import com.eventticket.dto.TicketCreateRequest;
import com.eventticket.dto.TicketCreateResponse;
import com.eventticket.entity.*;
import com.eventticket.repository.*;
import com.eventticket.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class TicketService {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ParticipantService participantService;

    @Autowired
    private TicketHistoryService ticketHistoryService;

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private TicketLockConfig lockConfig;

    @Autowired
    private SeatSectionConfig sectionConfig;

    @Autowired
    private SeatLockService seatLockService;

    @Autowired
    private SeatSectionService seatSectionService;

    @Transactional
    public TicketCreateResponse createTicket(TicketCreateRequest request) {
        Event event = eventRepository.findById(request.getEventId()).orElse(null);
        if (event == null) {
            throw new RuntimeException("活动不存在");
        }

        if ("cancelled".equals(event.getEventStatus())) {
            throw new RuntimeException("活动已取消");
        }
        if ("ended".equals(event.getEventStatus())) {
            throw new RuntimeException("活动已结束");
        }

        String ticketType = request.getTicketType() != null ? request.getTicketType() : lockConfig.getDefaultTicketType();
        String preferredSection = request.getSection();

        if (preferredSection != null && !preferredSection.isEmpty() && !sectionConfig.isValidSection(preferredSection)) {
            log.warn("Invalid section code: {}, using default", preferredSection);
            preferredSection = sectionConfig.getDefaultSection();
        }

        Seat seat;
        if (request.getSeatId() != null && !request.getSeatId().isEmpty()) {
            seat = seatRepository.findByIdWithLock(request.getSeatId()).orElse(null);
            if (seat == null) {
                throw new RuntimeException("座位不存在");
            }
            if ("sold".equals(seat.getSeatStatus())) {
                throw new RuntimeException("座位已售出");
            }
            if (!event.getEventId().equals(seat.getEventId())) {
                throw new RuntimeException("座位不属于该活动");
            }
            seat = seatLockService.lockSeat(request.getSeatId(), ticketType);
        } else {
            seat = seatLockService.lockSeatAutoAssign(event.getEventId(), ticketType, preferredSection);
        }

        Participant participant = participantService.findOrCreateParticipant(
            request.getParticipantName(),
            request.getParticipantPhone(),
            request.getParticipantIdType(),
            request.getParticipantIdNumber()
        );

        int ticketPrice = request.getTicketPrice() != null ? request.getTicketPrice() : seat.getSeatPrice();

        Ticket ticket = new Ticket();
        ticket.setTicketId(IdGenerator.generateTicketId());
        ticket.setEventId(event.getEventId());
        ticket.setSeatId(seat.getSeatId());
        ticket.setParticipantId(participant.getParticipantId());
        ticket.setParticipantName(request.getParticipantName());
        ticket.setParticipantPhone(request.getParticipantPhone());
        ticket.setTicketPrice(ticketPrice);
        ticket.setTicketStatus("pending_payment");
        ticket.setPaymentMethod(request.getPaymentMethod());
        ticket.setTicketType(ticketType);
        ticket.setCreatedAt(LocalDateTime.now());

        ticketRepository.save(ticket);
        ticketHistoryService.recordBooking(ticket.getTicketId(), "票务预订创建成功");

        if (request.getPaymentMethod() != null && !request.getPaymentMethod().isEmpty()) {
            processPayment(ticket, seat, participant, request.getPaymentMethod());
        }

        analyticsService.updateMonthlyStatistics();

        TicketCreateResponse response = new TicketCreateResponse();
        response.setTicketId(ticket.getTicketId());
        response.setTicketNumber(ticket.getTicketId());
        response.setStatus(ticket.getTicketStatus());
        response.setTicketPrice(ticket.getTicketPrice());

        return response;
    }

    @Transactional
    public void processPayment(Ticket ticket, Seat seat, Participant participant, String paymentMethod) {
        boolean paymentSuccess = simulatePayment(ticket.getTicketPrice(), paymentMethod);

        if (paymentSuccess) {
            ticket.setTicketStatus("confirmed");
            ticket.setConfirmedAt(LocalDateTime.now());
            ticket.setPaymentMethod(paymentMethod);
            ticketRepository.save(ticket);

            seatLockService.confirmSeatLock(seat.getSeatId());

            ticketHistoryService.recordPayment(ticket.getTicketId(), "支付成功");
        } else {
            ticket.setTicketStatus("cancelled");
            ticket.setCancelledAt(LocalDateTime.now());
            ticketRepository.save(ticket);

            seatLockService.releaseSeatLock(seat.getSeatId());

            ticketHistoryService.recordCancellation(ticket.getTicketId(), "支付失败，票务已取消");
        }
    }

    private boolean simulatePayment(int amount, String paymentMethod) {
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<Ticket> getTicketById(String ticketId) {
        return ticketRepository.findById(ticketId);
    }

    @Transactional(readOnly = true)
    public List<Ticket> getTicketsByEventId(String eventId) {
        return ticketRepository.findByEventId(eventId);
    }

    @Transactional(readOnly = true)
    public List<Ticket> getTicketsByParticipantPhone(String phone) {
        return ticketRepository.findByParticipantPhone(phone);
    }

    @Transactional
    public Ticket updateTicketStatus(String ticketId, String status) {
        return ticketRepository.findById(ticketId).map(ticket -> {
            ticket.setTicketStatus(status);
            if ("confirmed".equals(status)) {
                ticket.setConfirmedAt(LocalDateTime.now());
            } else if ("cancelled".equals(status)) {
                ticket.setCancelledAt(LocalDateTime.now());
            } else if ("used".equals(status)) {
                ticket.setUsedAt(LocalDateTime.now());
            }
            ticketRepository.save(ticket);
            return ticket;
        }).orElse(null);
    }

    @Transactional
    public boolean confirmTicketPayment(String ticketId) {
        Optional<Ticket> ticketOpt = ticketRepository.findById(ticketId);
        if (!ticketOpt.isPresent()) {
            return false;
        }
        Ticket ticket = ticketOpt.get();
        if (!"pending_payment".equals(ticket.getTicketStatus())) {
            return false;
        }

        Optional<Seat> seatOpt = seatRepository.findById(ticket.getSeatId());
        if (!seatOpt.isPresent()) {
            return false;
        }
        Seat seat = seatOpt.get();

        Participant participant = participantService.findOrCreateParticipant(
            ticket.getParticipantName(),
            ticket.getParticipantPhone(),
            null,
            null
        );

        ticket.setTicketStatus("confirmed");
        ticket.setConfirmedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        seatLockService.confirmSeatLock(seat.getSeatId());

        ticketHistoryService.recordPayment(ticketId, "支付确认成功");
        analyticsService.updateMonthlyStatistics();

        return true;
    }

    @Transactional(readOnly = true)
    public List<Ticket> getTicketsByParticipantId(String participantId) {
        return ticketRepository.findByParticipantId(participantId);
    }

    public int getLockTimeoutSeconds(String ticketType) {
        return lockConfig.getLockTimeoutSeconds(ticketType);
    }

    public int getPaymentTimeoutMinutes(String ticketType) {
        return lockConfig.getPaymentTimeoutMinutes(ticketType);
    }
}
