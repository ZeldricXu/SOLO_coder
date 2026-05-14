package com.eventticket.builder;

import com.eventticket.entity.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static Event createConcertEvent(String eventId) {
        Event event = new Event();
        event.setEventId(eventId);
        event.setEventName("2026年度演唱会");
        event.setEventType("concert");
        event.setEventDate(LocalDateTime.now().plusDays(30));
        event.setEventVenue("国家体育馆");
        event.setEventCapacity(5000);
        event.setEventStatus("scheduled");
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }

    public static Event createLargeConcertEvent(String eventId) {
        Event event = new Event();
        event.setEventId(eventId);
        event.setEventName("2026大型音乐节");
        event.setEventType("concert");
        event.setEventDate(LocalDateTime.now().plusDays(60));
        event.setEventVenue("上海梅赛德斯文化中心");
        event.setEventCapacity(20000);
        event.setEventStatus("scheduled");
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }

    public static Event createSmallEvent(String eventId) {
        Event event = new Event();
        event.setEventId(eventId);
        event.setEventName("小型分享会");
        event.setEventType("workshop");
        event.setEventDate(LocalDateTime.now().plusDays(7));
        event.setEventVenue("创业空间");
        event.setEventCapacity(100);
        event.setEventStatus("scheduled");
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }

    public static Event createCancelledEvent(String eventId) {
        Event event = createConcertEvent(eventId);
        event.setEventStatus("cancelled");
        return event;
    }

    public static Event createEndedEvent(String eventId) {
        Event event = createConcertEvent(eventId);
        event.setEventStatus("ended");
        event.setEventDate(LocalDateTime.now().minusDays(7));
        return event;
    }

    public static Seat createVIPSeat(String seatId, String eventId) {
        Seat seat = new Seat();
        seat.setSeatId(seatId);
        seat.setEventId(eventId);
        seat.setSeatNumber("V001");
        seat.setSeatSection("VIP");
        seat.setSeatPrice(1800);
        seat.setSeatStatus("available");
        seat.setCreatedAt(LocalDateTime.now());
        return seat;
    }

    public static Seat createRegularSeat(String seatId, String eventId) {
        Seat seat = new Seat();
        seat.setSeatId(seatId);
        seat.setEventId(eventId);
        seat.setSeatNumber("A101");
        seat.setSeatSection("Regular");
        seat.setSeatPrice(500);
        seat.setSeatStatus("available");
        seat.setCreatedAt(LocalDateTime.now());
        return seat;
    }

    public static Seat createLockedSeat(String seatId, String eventId, String section) {
        Seat seat;
        if ("VIP".equals(section)) {
            seat = createVIPSeat(seatId, eventId);
        } else {
            seat = createRegularSeat(seatId, eventId);
        }
        seat.setSeatStatus("locked");
        seat.setLockedAt(LocalDateTime.now());
        return seat;
    }

    public static Seat createSoldSeat(String seatId, String eventId, String section) {
        Seat seat;
        if ("VIP".equals(section)) {
            seat = createVIPSeat(seatId, eventId);
        } else {
            seat = createRegularSeat(seatId, eventId);
        }
        seat.setSeatStatus("sold");
        seat.setLockedAt(LocalDateTime.now().minusMinutes(5));
        seat.setSoldAt(LocalDateTime.now().minusMinutes(3));
        return seat;
    }

    public static Seat createAdmittedSeat(String seatId, String eventId, String section) {
        Seat seat = createSoldSeat(seatId, eventId, section);
        seat.setSeatStatus("admitted");
        seat.setAdmittedAt(LocalDateTime.now().minusMinutes(30));
        return seat;
    }

    public static List<Seat> createMultipleSeats(String eventId, int count, String section) {
        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String seatId = "seat_" + section.toLowerCase() + "_" + String.format("%03d", i);
            Seat seat;
            if ("VIP".equals(section)) {
                seat = createVIPSeat(seatId, eventId);
                seat.setSeatNumber("V" + String.format("%03d", i));
            } else {
                seat = createRegularSeat(seatId, eventId);
                seat.setSeatNumber("A" + String.format("%03d", i));
            }
            seats.add(seat);
        }
        return seats;
    }

    public static Participant createParticipant(String participantId) {
        Participant participant = new Participant();
        participant.setParticipantId(participantId);
        participant.setParticipantName("张三");
        participant.setParticipantPhone("13800138000");
        participant.setParticipantIdType("identity");
        participant.setParticipantIdNumber("110101199001011234");
        participant.setCreatedAt(LocalDateTime.now());
        return participant;
    }

    public static Participant createParticipant(String participantId, String name, String phone) {
        Participant participant = new Participant();
        participant.setParticipantId(participantId);
        participant.setParticipantName(name);
        participant.setParticipantPhone(phone);
        participant.setParticipantIdType("identity");
        participant.setParticipantIdNumber("11010119900101" + String.format("%04d", Math.abs(phone.hashCode() % 10000)));
        participant.setCreatedAt(LocalDateTime.now());
        return participant;
    }

    public static Ticket createPendingPaymentTicket(String ticketId, String eventId, String seatId) {
        Ticket ticket = new Ticket();
        ticket.setTicketId(ticketId);
        ticket.setEventId(eventId);
        ticket.setSeatId(seatId);
        ticket.setParticipantId("participant_001");
        ticket.setParticipantName("张三");
        ticket.setParticipantPhone("13800138000");
        ticket.setTicketStatus("pending_payment");
        ticket.setTicketPrice(500);
        ticket.setPaymentMethod("wechat");
        ticket.setCreatedAt(LocalDateTime.now());
        return ticket;
    }

    public static Ticket createConfirmedTicket(String ticketId, String eventId, String seatId) {
        Ticket ticket = createPendingPaymentTicket(ticketId, eventId, seatId);
        ticket.setTicketStatus("confirmed");
        ticket.setConfirmedAt(LocalDateTime.now().minusMinutes(10));
        return ticket;
    }

    public static Ticket createUsedTicket(String ticketId, String eventId, String seatId) {
        Ticket ticket = createConfirmedTicket(ticketId, eventId, seatId);
        ticket.setTicketStatus("used");
        ticket.setUsedAt(LocalDateTime.now().minusMinutes(30));
        return ticket;
    }

    public static Ticket createCancelledTicket(String ticketId, String eventId, String seatId) {
        Ticket ticket = createPendingPaymentTicket(ticketId, eventId, seatId);
        ticket.setTicketStatus("cancelled");
        ticket.setCancelledAt(LocalDateTime.now().minusMinutes(5));
        return ticket;
    }

    public static Ticket createVIPConfirmedTicket(String ticketId, String eventId, String seatId) {
        Ticket ticket = createConfirmedTicket(ticketId, eventId, seatId);
        ticket.setTicketPrice(1800);
        return ticket;
    }

    public static Verification createValidVerification(String verifyId, String ticketId) {
        Verification verification = new Verification();
        verification.setVerifyId(verifyId);
        verification.setTicketId(ticketId);
        verification.setVerifyTime(LocalDateTime.now());
        verification.setVerifyResult("valid");
        verification.setVerifyOperator("staff_001");
        return verification;
    }

    public static Verification createInvalidVerification(String verifyId, String ticketId) {
        Verification verification = new Verification();
        verification.setVerifyId(verifyId);
        verification.setTicketId(ticketId);
        verification.setVerifyTime(LocalDateTime.now());
        verification.setVerifyResult("invalid");
        verification.setVerifyOperator("staff_001");
        return verification;
    }

    public static ChangeRecord createRefundRecord(String changeId, String ticketId, int amount) {
        ChangeRecord record = new ChangeRecord();
        record.setChangeId(changeId);
        record.setTicketId(ticketId);
        record.setChangeType("refund");
        record.setChangeReason("行程变更");
        record.setChangeAmount(amount);
        record.setChangeStatus("approved");
        record.setChangeTime(LocalDateTime.now());
        return record;
    }

    public static ChangeRecord createExchangeRecord(String changeId, String ticketId, int amount) {
        ChangeRecord record = new ChangeRecord();
        record.setChangeId(changeId);
        record.setTicketId(ticketId);
        record.setChangeType("exchange");
        record.setChangeReason("座位调整");
        record.setChangeAmount(amount);
        record.setChangeStatus("approved");
        record.setChangeTime(LocalDateTime.now());
        return record;
    }

    public static EventSchedule createEventSchedule(String scheduleId, String eventId) {
        EventSchedule schedule = new EventSchedule();
        schedule.setScheduleId(scheduleId);
        schedule.setEventId(eventId);
        schedule.setScheduleTitle("开场表演");
        schedule.setScheduleStartTime(LocalDateTime.now().plusDays(30).withHour(19).withMinute(0));
        schedule.setScheduleEndTime(LocalDateTime.now().plusDays(30).withHour(19).withMinute(30));
        schedule.setScheduleVenue("主舞台");
        schedule.setScheduleDescription("乐队开场表演");
        schedule.setCreatedAt(LocalDateTime.now());
        return schedule;
    }

    public static Statistics createMonthlyStatistics(String statMonth) {
        Statistics stats = new Statistics();
        stats.setStatId("stat_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        stats.setStatMonth(statMonth);
        stats.setEventCount(10);
        stats.setTicketCount(5000);
        stats.setTotalAmount(2500000L);
        stats.setAdmissionCount(4800);
        return stats;
    }

    public static TicketHistory createBookingHistory(String historyId, String ticketId) {
        TicketHistory history = new TicketHistory();
        history.setHistoryId(historyId);
        history.setTicketId(ticketId);
        history.setActionType("BOOKING");
        history.setActionTime(LocalDateTime.now());
        history.setActionDescription("票务预订创建成功");
        history.setOperator("SYSTEM");
        return history;
    }

    public static TicketHistory createPaymentHistory(String historyId, String ticketId) {
        TicketHistory history = new TicketHistory();
        history.setHistoryId(historyId);
        history.setTicketId(ticketId);
        history.setActionType("PAYMENT");
        history.setActionTime(LocalDateTime.now());
        history.setActionDescription("支付成功");
        history.setOperator("SYSTEM");
        return history;
    }

    public static TicketHistory createVerificationHistory(String historyId, String ticketId) {
        TicketHistory history = new TicketHistory();
        history.setHistoryId(historyId);
        history.setTicketId(ticketId);
        history.setActionType("VERIFICATION");
        history.setActionTime(LocalDateTime.now());
        history.setActionDescription("验证通过，入场成功");
        history.setOperator("staff_001");
        return history;
    }

    public static String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
