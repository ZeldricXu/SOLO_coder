package com.eventticket.service;

import com.eventticket.builder.MockConfig;
import com.eventticket.builder.TestDataBuilder;
import com.eventticket.dto.TicketCreateRequest;
import com.eventticket.dto.TicketCreateResponse;
import com.eventticket.entity.*;
import com.eventticket.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("票务模块单元测试")
class TicketServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private ParticipantService participantService;

    @Mock
    private TicketHistoryService ticketHistoryService;

    @Mock
    private AnalyticsService analyticsService;

    @InjectMocks
    private TicketService ticketService;

    @Test
    @DisplayName("票务预订前获取锁正确性测试")
    void testSeatLockBeforeBooking() {
        String eventId = "event_lock_test";
        String seatId = "seat_lock_001";
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat availableSeat = TestDataBuilder.createRegularSeat(seatId, eventId);
        Participant participant = TestDataBuilder.createParticipant("participant_001");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.of(availableSeat));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> {
            Seat seat = invocation.getArgument(0);
            return seat;
        });
        when(participantService.findOrCreateParticipant(anyString(), anyString(), any(), any()))
            .thenReturn(participant);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketCreateRequest request = new TicketCreateRequest();
        request.setEventId(eventId);
        request.setSeatId(seatId);
        request.setParticipantName("张三");
        request.setParticipantPhone("13800138000");

        TicketCreateResponse response = ticketService.createTicket(request);

        assertNotNull(response);
        assertEquals("pending_payment", response.getStatus());
        verify(seatRepository, times(1)).findByIdWithLock(seatId);
        verify(seatRepository, atLeastOnce()).save(any(Seat.class));
        verify(ticketRepository, times(1)).save(any(Ticket.class));
    }

    @Test
    @DisplayName("并发预订时锁冲突处理测试")
    void testConcurrentBookingLockConflict() {
        String eventId = "event_concurrent_test";
        String seatId = "seat_concurrent_001";
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat lockedSeat = TestDataBuilder.createLockedSeat(seatId, eventId, "Regular");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.of(lockedSeat));

        TicketCreateRequest request = new TicketCreateRequest();
        request.setEventId(eventId);
        request.setSeatId(seatId);
        request.setParticipantName("张三");
        request.setParticipantPhone("13800138000");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            ticketService.createTicket(request);
        });

        assertEquals("座位已售出", exception.getMessage());
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    @DisplayName("VIP票锁定超时差异测试 - VIP票短超时")
    void testVIPSeatLockTimeout() {
        String eventId = "event_vip_timeout";
        String seatId = "seat_vip_timeout";
        int expectedTimeout = MockConfig.VIP_LOCK_TIMEOUT_SECONDS;

        assertEquals(300, expectedTimeout);
        assertNotEquals(MockConfig.REGULAR_LOCK_TIMEOUT_SECONDS, expectedTimeout);
        assertTrue(expectedTimeout < MockConfig.REGULAR_LOCK_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("普通票锁定超时差异测试 - 普通票长超时")
    void testRegularSeatLockTimeout() {
        String eventId = "event_reg_timeout";
        String seatId = "seat_reg_timeout";
        int expectedTimeout = MockConfig.REGULAR_LOCK_TIMEOUT_SECONDS;

        assertEquals(900, expectedTimeout);
        assertTrue(expectedTimeout > MockConfig.VIP_LOCK_TIMEOUT_SECONDS);
        assertEquals(3 * MockConfig.VIP_LOCK_TIMEOUT_SECONDS, expectedTimeout);
    }

    @Test
    @DisplayName("不同票种锁定超时比较测试")
    void testLockTimeoutDifferenceBetweenTicketTypes() {
        int vipTimeout = MockConfig.getLockTimeout("VIP");
        int regularTimeout = MockConfig.getLockTimeout("Regular");

        assertEquals(300, vipTimeout);
        assertEquals(900, regularTimeout);
        assertEquals(3, regularTimeout / vipTimeout);
    }

    @Test
    @DisplayName("座位状态流转 - 空闲到锁定（预订流程）")
    void testStateFlowAvailableToLocked() {
        String eventId = "event_flow_1";
        String seatId = "seat_flow_1";
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat availableSeat = TestDataBuilder.createRegularSeat(seatId, eventId);
        Participant participant = TestDataBuilder.createParticipant("participant_flow_1");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.of(availableSeat));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(participantService.findOrCreateParticipant(anyString(), anyString(), any(), any()))
            .thenReturn(participant);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketCreateRequest request = new TicketCreateRequest();
        request.setEventId(eventId);
        request.setSeatId(seatId);
        request.setParticipantName("张三");
        request.setParticipantPhone("13800138000");

        TicketCreateResponse response = ticketService.createTicket(request);

        assertNotNull(response);
        verify(seatRepository).save(argThat(seat -> 
            seat.getSeatId().equals(seatId) && "locked".equals(seat.getSeatStatus())
        ));
    }

    @Test
    @DisplayName("座位状态流转 - 锁定到已售（支付成功）")
    void testStateFlowLockedToSold() {
        String eventId = "event_flow_2";
        String seatId = "seat_flow_2";
        Seat lockedSeat = TestDataBuilder.createLockedSeat(seatId, eventId, "Regular");
        Participant participant = TestDataBuilder.createParticipant("participant_flow_2");
        Ticket pendingTicket = TestDataBuilder.createPendingPaymentTicket("ticket_flow_2", eventId, seatId);

        when(seatRepository.findById(seatId)).thenReturn(Optional.of(lockedSeat));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ticketService.processPayment(pendingTicket, lockedSeat, participant, "wechat");

        verify(seatRepository).save(argThat(seat -> 
            seat.getSeatId().equals(seatId) && "sold".equals(seat.getSeatStatus())
        ));
        verify(ticketRepository).save(argThat(ticket -> 
            ticket.getTicketId().equals("ticket_flow_2") && "confirmed".equals(ticket.getTicketStatus())
        ));
    }

    @Test
    @DisplayName("座位状态流转 - 已售到已入场（验证成功）")
    void testStateFlowSoldToAdmitted() {
        String ticketId = "ticket_flow_3";
        String eventId = "event_flow_3";
        String seatId = "seat_flow_3";
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(TestDataBuilder.createConcertEvent(eventId)));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Ticket updatedTicket = ticketService.updateTicketStatus(ticketId, "used");

        assertNotNull(updatedTicket);
        assertEquals("used", updatedTicket.getTicketStatus());
    }

    @Test
    @DisplayName("座位状态流转 - 完整流程验证")
    void testCompleteSeatStatusFlow() {
        String eventId = "event_complete_flow";
        String seatId = "seat_complete_flow";
        String ticketId = "ticket_complete_flow";
        
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat availableSeat = TestDataBuilder.createRegularSeat(seatId, eventId);
        Participant participant = TestDataBuilder.createParticipant("participant_complete");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.of(availableSeat));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(participantService.findOrCreateParticipant(anyString(), anyString(), any(), any()))
            .thenReturn(participant);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> {
            Ticket ticket = invocation.getArgument(0);
            if (ticket.getTicketId() == null) {
                ticket.setTicketId(ticketId);
            }
            return ticket;
        });

        TicketCreateRequest request = new TicketCreateRequest();
        request.setEventId(eventId);
        request.setSeatId(seatId);
        request.setParticipantName("张三");
        request.setParticipantPhone("13800138000");

        TicketCreateResponse response = ticketService.createTicket(request);
        assertNotNull(response);
        assertEquals("pending_payment", response.getStatus());

        Ticket pendingTicket = TestDataBuilder.createPendingPaymentTicket(ticketId, eventId, seatId);
        Seat lockedSeat = TestDataBuilder.createLockedSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(pendingTicket));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(lockedSeat));

        boolean confirmed = ticketService.confirmTicketPayment(ticketId);
        
        assertTrue(confirmed);
        verify(ticketRepository).save(argThat(t -> "confirmed".equals(t.getTicketStatus())));
        verify(seatRepository).save(argThat(s -> "sold".equals(s.getSeatStatus())));

        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));

        Ticket usedTicket = ticketService.updateTicketStatus(ticketId, "used");
        
        assertNotNull(usedTicket);
        assertEquals("used", usedTicket.getTicketStatus());
    }

    @Test
    @DisplayName("活动不存在时预订失败")
    void testBookingWhenEventNotExist() {
        String eventId = "event_not_exist";

        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        TicketCreateRequest request = new TicketCreateRequest();
        request.setEventId(eventId);
        request.setParticipantName("张三");
        request.setParticipantPhone("13800138000");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            ticketService.createTicket(request);
        });

        assertEquals("活动不存在", exception.getMessage());
    }

    @Test
    @DisplayName("活动已取消时预订失败")
    void testBookingWhenEventCancelled() {
        String eventId = "event_cancelled";
        Event cancelledEvent = TestDataBuilder.createCancelledEvent(eventId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(cancelledEvent));

        TicketCreateRequest request = new TicketCreateRequest();
        request.setEventId(eventId);
        request.setParticipantName("张三");
        request.setParticipantPhone("13800138000");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            ticketService.createTicket(request);
        });

        assertEquals("活动已取消", exception.getMessage());
    }

    @Test
    @DisplayName("活动已结束时预订失败")
    void testBookingWhenEventEnded() {
        String eventId = "event_ended";
        Event endedEvent = TestDataBuilder.createEndedEvent(eventId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(endedEvent));

        TicketCreateRequest request = new TicketCreateRequest();
        request.setEventId(eventId);
        request.setParticipantName("张三");
        request.setParticipantPhone("13800138000");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            ticketService.createTicket(request);
        });

        assertEquals("活动已结束", exception.getMessage());
    }

    @Test
    @DisplayName("座位不存在时预订失败")
    void testBookingWhenSeatNotExist() {
        String eventId = "event_seat_not_exist";
        String seatId = "seat_not_exist";
        Event event = TestDataBuilder.createConcertEvent(eventId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.empty());

        TicketCreateRequest request = new TicketCreateRequest();
        request.setEventId(eventId);
        request.setSeatId(seatId);
        request.setParticipantName("张三");
        request.setParticipantPhone("13800138000");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            ticketService.createTicket(request);
        });

        assertEquals("座位不存在", exception.getMessage());
    }

    @Test
    @DisplayName("无可用座位时自动分配失败")
    void testBookingWhenNoAvailableSeats() {
        String eventId = "event_no_seats";
        Event event = TestDataBuilder.createConcertEvent(eventId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findAvailableSeatsSorted(eventId)).thenReturn(List.of());

        TicketCreateRequest request = new TicketCreateRequest();
        request.setEventId(eventId);
        request.setParticipantName("张三");
        request.setParticipantPhone("13800138000");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            ticketService.createTicket(request);
        });

        assertEquals("没有可用座位", exception.getMessage());
    }

    @Test
    @DisplayName("支付成功后状态更新测试")
    void testPaymentSuccessUpdatesStatus() {
        String eventId = "event_payment_success";
        String seatId = "seat_payment_success";
        Seat lockedSeat = TestDataBuilder.createLockedSeat(seatId, eventId, "Regular");
        Participant participant = TestDataBuilder.createParticipant("participant_payment");
        Ticket pendingTicket = TestDataBuilder.createPendingPaymentTicket("ticket_payment", eventId, seatId);

        when(seatRepository.findById(seatId)).thenReturn(Optional.of(lockedSeat));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ticketService.processPayment(pendingTicket, lockedSeat, participant, "wechat");

        verify(ticketRepository).save(argThat(t -> 
            "confirmed".equals(t.getTicketStatus()) && t.getConfirmedAt() != null
        ));
        verify(seatRepository).save(argThat(s -> 
            "sold".equals(s.getSeatStatus()) && s.getSoldAt() != null
        ));
        verify(ticketHistoryService, times(1)).recordPayment(anyString(), anyString());
    }

    @Test
    @DisplayName("获取票务信息测试")
    void testGetTicketById() {
        String ticketId = "ticket_get_test";
        String eventId = "event_get_test";
        String seatId = "seat_get_test";
        Ticket ticket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        Optional<Ticket> result = ticketService.getTicketById(ticketId);

        assertTrue(result.isPresent());
        assertEquals(ticketId, result.get().getTicketId());
        assertEquals("confirmed", result.get().getTicketStatus());
    }

    @Test
    @DisplayName("获取活动票务列表测试")
    void testGetTicketsByEventId() {
        String eventId = "event_tickets_test";
        List<Ticket> tickets = List.of(
            TestDataBuilder.createConfirmedTicket("ticket_1", eventId, "seat_1"),
            TestDataBuilder.createConfirmedTicket("ticket_2", eventId, "seat_2"),
            TestDataBuilder.createUsedTicket("ticket_3", eventId, "seat_3")
        );

        when(ticketRepository.findByEventId(eventId)).thenReturn(tickets);

        List<Ticket> result = ticketService.getTicketsByEventId(eventId);

        assertNotNull(result);
        assertEquals(3, result.size());
        verify(ticketRepository, times(1)).findByEventId(eventId);
    }

    @Test
    @DisplayName("获取参与者票务列表测试")
    void testGetTicketsByParticipantPhone() {
        String phone = "13800138000";
        List<Ticket> tickets = List.of(
            TestDataBuilder.createConfirmedTicket("ticket_1", "event_1", "seat_1"),
            TestDataBuilder.createConfirmedTicket("ticket_2", "event_2", "seat_2")
        );

        when(ticketRepository.findByParticipantPhone(phone)).thenReturn(tickets);

        List<Ticket> result = ticketService.getTicketsByParticipantPhone(phone);

        assertNotNull(result);
        assertEquals(2, result.size());
        verify(ticketRepository, times(1)).findByParticipantPhone(phone);
    }

    @Test
    @DisplayName("支付确认测试")
    void testConfirmTicketPayment() {
        String ticketId = "ticket_confirm";
        String eventId = "event_confirm";
        String seatId = "seat_confirm";
        Ticket pendingTicket = TestDataBuilder.createPendingPaymentTicket(ticketId, eventId, seatId);
        Seat lockedSeat = TestDataBuilder.createLockedSeat(seatId, eventId, "Regular");
        Participant participant = TestDataBuilder.createParticipant("participant_confirm");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(pendingTicket));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(lockedSeat));
        when(participantService.findOrCreateParticipant(anyString(), anyString(), any(), any()))
            .thenReturn(participant);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = ticketService.confirmTicketPayment(ticketId);

        assertTrue(result);
        verify(ticketRepository, times(1)).save(any(Ticket.class));
        verify(seatRepository, times(1)).save(any(Seat.class));
        verify(ticketHistoryService, times(1)).recordPayment(eq(ticketId), anyString());
    }

    @Test
    @DisplayName("非待支付状态的票务不能确认支付")
    void testConfirmPaymentForNonPendingTicket() {
        String ticketId = "ticket_confirm_fail";
        String eventId = "event_confirm_fail";
        String seatId = "seat_confirm_fail";
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));

        boolean result = ticketService.confirmTicketPayment(ticketId);

        assertFalse(result);
        verify(ticketRepository, never()).save(any(Ticket.class));
        verify(seatRepository, never()).save(any(Seat.class));
    }

    @Test
    @DisplayName("票务不存在时确认支付失败")
    void testConfirmPaymentWhenTicketNotExist() {
        String ticketId = "ticket_not_exist";

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        boolean result = ticketService.confirmTicketPayment(ticketId);

        assertFalse(result);
    }

    @Test
    @DisplayName("票务状态更新测试")
    void testUpdateTicketStatus() {
        String ticketId = "ticket_status_update";
        String eventId = "event_status_update";
        String seatId = "seat_status_update";
        Ticket ticket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Ticket updatedTicket = ticketService.updateTicketStatus(ticketId, "used");

        assertNotNull(updatedTicket);
        assertEquals("used", updatedTicket.getTicketStatus());
        assertNotNull(updatedTicket.getUsedAt());
    }

    @Test
    @DisplayName("票务不存在时状态更新返回null")
    void testUpdateStatusWhenTicketNotExist() {
        String ticketId = "ticket_not_exist_status";

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        Ticket result = ticketService.updateTicketStatus(ticketId, "used");

        assertNull(result);
        verify(ticketRepository, never()).save(any(Ticket.class));
    }
}
