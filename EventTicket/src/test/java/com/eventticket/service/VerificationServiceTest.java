package com.eventticket.service;

import com.eventticket.builder.MockConfig;
import com.eventticket.builder.TestDataBuilder;
import com.eventticket.dto.TicketVerifyRequest;
import com.eventticket.dto.TicketVerifyResponse;
import com.eventticket.entity.*;
import com.eventticket.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("验证模块单元测试")
class VerificationServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private VerificationRepository verificationRepository;

    @Mock
    private TicketHistoryService ticketHistoryService;

    @Mock
    private AnalyticsService analyticsService;

    @InjectMocks
    private VerificationService verificationService;

    @Test
    @DisplayName("验证有效性判断 - 有效票务验证通过")
    void testValidTicketVerification() {
        String ticketId = "ticket_valid";
        String eventId = "event_valid";
        String seatId = "seat_valid";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);
        request.setOperator("staff_001");

        TicketVerifyResponse response = verificationService.verifyTicket(request);

        assertNotNull(response);
        assertEquals("valid", response.getVerifyResult());
        assertEquals(event.getEventName(), response.getEventName());
        assertEquals(soldSeat.getSeatNumber(), response.getSeatNumber());
        assertEquals(confirmedTicket.getParticipantName(), response.getParticipantName());
        verify(verificationRepository, times(1)).save(any(Verification.class));
        verify(ticketHistoryService, times(1)).recordVerification(eq(ticketId), anyString(), eq("staff_001"));
    }

    @Test
    @DisplayName("验证有效性判断 - 票务不存在验证失败")
    void testVerificationWhenTicketNotExist() {
        String ticketId = "ticket_not_exist";

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);

        TicketVerifyResponse response = verificationService.verifyTicket(request);

        assertNotNull(response);
        assertEquals("invalid", response.getVerifyResult());
        verify(verificationRepository, times(1)).save(argThat(v -> 
            "invalid".equals(v.getVerifyResult())
        ));
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    @DisplayName("验证有效性判断 - 已取消票务验证失败")
    void testVerificationWhenTicketCancelled() {
        String ticketId = "ticket_cancelled";
        String eventId = "event_cancelled";
        String seatId = "seat_cancelled";
        
        Ticket cancelledTicket = TestDataBuilder.createCancelledTicket(ticketId, eventId, seatId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(cancelledTicket));
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);

        TicketVerifyResponse response = verificationService.verifyTicket(request);

        assertNotNull(response);
        assertEquals("cancelled", response.getVerifyResult());
        verify(ticketHistoryService, times(1)).recordVerification(eq(ticketId), contains("已取消"), anyString());
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    @DisplayName("验证有效性判断 - 已使用票务验证失败")
    void testVerificationWhenTicketAlreadyUsed() {
        String ticketId = "ticket_used";
        String eventId = "event_used";
        String seatId = "seat_used";
        
        Ticket usedTicket = TestDataBuilder.createUsedTicket(ticketId, eventId, seatId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(usedTicket));
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);

        TicketVerifyResponse response = verificationService.verifyTicket(request);

        assertNotNull(response);
        assertEquals("already_used", response.getVerifyResult());
        verify(ticketHistoryService, times(1)).recordVerification(eq(ticketId), contains("已使用"), anyString());
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    @DisplayName("验证有效性判断 - 待支付状态票务验证失败")
    void testVerificationWhenTicketPendingPayment() {
        String ticketId = "ticket_pending";
        String eventId = "event_pending";
        String seatId = "seat_pending";
        
        Ticket pendingTicket = TestDataBuilder.createPendingPaymentTicket(ticketId, eventId, seatId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(pendingTicket));
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);

        TicketVerifyResponse response = verificationService.verifyTicket(request);

        assertNotNull(response);
        assertEquals("invalid_status", response.getVerifyResult());
        verify(ticketHistoryService, times(1)).recordVerification(eq(ticketId), contains("无效"), anyString());
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    @DisplayName("验证成功后票务状态更新")
    void testVerificationUpdatesTicketStatusToUsed() {
        String ticketId = "ticket_update_status";
        String eventId = "event_update_status";
        String seatId = "seat_update_status";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);

        verificationService.verifyTicket(request);

        verify(ticketRepository).save(argThat(t -> 
            "used".equals(t.getTicketStatus()) && t.getUsedAt() != null
        ));
    }

    @Test
    @DisplayName("验证成功后座位状态更新")
    void testVerificationUpdatesSeatStatusToAdmitted() {
        String ticketId = "ticket_update_seat";
        String eventId = "event_update_seat";
        String seatId = "seat_update_seat";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);

        verificationService.verifyTicket(request);

        verify(seatRepository).save(argThat(s -> 
            "admitted".equals(s.getSeatStatus()) && s.getAdmittedAt() != null
        ));
    }

    @Test
    @DisplayName("小型活动重试次数测试")
    void testSmallEventRetryCount() {
        Event smallEvent = TestDataBuilder.createSmallEvent("event_small");
        int retryCount = MockConfig.getRetryCount(smallEvent.getEventCapacity());

        assertEquals(MockConfig.SMALL_EVENT_RETRY_COUNT, retryCount);
        assertEquals(2, retryCount);
    }

    @Test
    @DisplayName("中型活动重试次数测试")
    void testMediumEventRetryCount() {
        Event mediumEvent = TestDataBuilder.createConcertEvent("event_medium");
        int retryCount = MockConfig.getRetryCount(mediumEvent.getEventCapacity());

        assertEquals(MockConfig.MEDIUM_EVENT_RETRY_COUNT, retryCount);
        assertEquals(3, retryCount);
    }

    @Test
    @DisplayName("大型活动重试次数测试")
    void testLargeEventRetryCount() {
        Event largeEvent = TestDataBuilder.createLargeConcertEvent("event_large");
        int retryCount = MockConfig.getRetryCount(largeEvent.getEventCapacity());

        assertEquals(MockConfig.LARGE_EVENT_RETRY_COUNT, retryCount);
        assertEquals(5, retryCount);
    }

    @Test
    @DisplayName("不同活动规模重试次数比较测试")
    void testRetryCountComparison() {
        int smallRetry = MockConfig.getRetryCount(500);
        int mediumRetry = MockConfig.getRetryCount(5000);
        int largeRetry = MockConfig.getRetryCount(15000);

        assertTrue(smallRetry < mediumRetry);
        assertTrue(mediumRetry < largeRetry);
        assertEquals(2, smallRetry);
        assertEquals(3, mediumRetry);
        assertEquals(5, largeRetry);
    }

    @Test
    @DisplayName("验证记录持久化测试")
    void testVerificationRecordPersisted() {
        String ticketId = "ticket_record";
        String eventId = "event_record";
        String seatId = "seat_record";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);
        request.setOperator("staff_002");

        verificationService.verifyTicket(request);

        verify(verificationRepository).save(argThat(v -> 
            "valid".equals(v.getVerifyResult()) && 
            "staff_002".equals(v.getVerifyOperator()) &&
            ticketId.equals(v.getTicketId())
        ));
    }

    @Test
    @DisplayName("验证历史记录记录测试")
    void testVerificationHistoryRecorded() {
        String ticketId = "ticket_history";
        String eventId = "event_history";
        String seatId = "seat_history";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);
        request.setOperator("staff_003");

        verificationService.verifyTicket(request);

        verify(ticketHistoryService, times(1)).recordVerification(
            eq(ticketId),
            anyString(),
            eq("staff_003")
        );
    }

    @Test
    @DisplayName("验证后统计数据更新")
    void testVerificationUpdatesAnalytics() {
        String ticketId = "ticket_analytics";
        String eventId = "event_analytics";
        String seatId = "seat_analytics";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);

        verificationService.verifyTicket(request);

        verify(analyticsService, times(1)).updateMonthlyStatistics();
    }

    @Test
    @DisplayName("获取验证记录测试")
    void testGetVerificationById() {
        String verifyId = "verify_001";
        String ticketId = "ticket_verify_get";
        Verification verification = TestDataBuilder.createValidVerification(verifyId, ticketId);

        when(verificationRepository.findById(verifyId)).thenReturn(Optional.of(verification));

        Optional<Verification> result = verificationService.getVerificationById(verifyId);

        assertTrue(result.isPresent());
        assertEquals(verifyId, result.get().getVerifyId());
        assertEquals("valid", result.get().getVerifyResult());
        verify(verificationRepository, times(1)).findById(verifyId);
    }

    @Test
    @DisplayName("获取票务验证记录列表测试")
    void testGetVerificationsByTicketId() {
        String ticketId = "ticket_verify_list";
        java.util.List<Verification> verifications = java.util.Arrays.asList(
            TestDataBuilder.createValidVerification("verify_1", ticketId),
            TestDataBuilder.createValidVerification("verify_2", ticketId)
        );

        when(verificationRepository.findByTicketId(ticketId)).thenReturn(verifications);

        java.util.List<Verification> result = verificationService.getVerificationsByTicketId(ticketId);

        assertNotNull(result);
        assertEquals(2, result.size());
        verify(verificationRepository, times(1)).findByTicketId(ticketId);
    }

    @Test
    @DisplayName("验证响应包含活动和座位信息")
    void testVerificationResponseIncludesEventAndSeatInfo() {
        String ticketId = "ticket_response_info";
        String eventId = "event_response_info";
        String seatId = "seat_response_info";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);

        TicketVerifyResponse response = verificationService.verifyTicket(request);

        assertNotNull(response);
        assertEquals(event.getEventName(), response.getEventName());
        assertEquals(soldSeat.getSeatNumber(), response.getSeatNumber());
        assertEquals(confirmedTicket.getParticipantName(), response.getParticipantName());
    }

    @Test
    @DisplayName("验证无效时不更新票务状态")
    void testInvalidVerificationDoesNotUpdateStatus() {
        String ticketId = "ticket_not_update";
        
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);

        verificationService.verifyTicket(request);

        verify(ticketRepository, never()).save(any(Ticket.class));
        verify(seatRepository, never()).save(any(Seat.class));
    }

    @Test
    @DisplayName("验证无效时不记录验证历史")
    void testInvalidVerificationDoesNotRecordSuccessHistory() {
        String ticketId = "ticket_not_record";
        
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());
        when(verificationRepository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TicketVerifyRequest request = new TicketVerifyRequest();
        request.setTicketId(ticketId);

        verificationService.verifyTicket(request);

        verify(ticketHistoryService, never()).recordVerification(anyString(), anyString(), anyString());
    }
}
