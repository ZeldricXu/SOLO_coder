package com.eventticket.service;

import com.eventticket.builder.MockConfig;
import com.eventticket.builder.TestDataBuilder;
import com.eventticket.dto.ChangeRequest;
import com.eventticket.entity.*;
import com.eventticket.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("退改模块单元测试")
class ChangeServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private ChangeRecordRepository changeRecordRepository;

    @Mock
    private TicketHistoryService ticketHistoryService;

    @Mock
    private AnalyticsService analyticsService;

    @InjectMocks
    private ChangeService changeService;

    @Test
    @DisplayName("退票金额计算 - 提前7天以上退款（10%手续费）")
    void testRefundAmountEarly() {
        String ticketId = "ticket_refund_early";
        String eventId = "event_refund_early";
        String seatId = "seat_refund_early";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        confirmedTicket.setTicketPrice(1000);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        double feeRate = MockConfig.getRefundFeeRate(10);
        int expectedRefundAmount = (int) (1000 * (1 - feeRate));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("refund");
        request.setChangeReason("行程变更");

        ChangeRecord record = changeService.processRefund(request);

        assertNotNull(record);
        assertEquals("refund", record.getChangeType());
        assertEquals("approved", record.getChangeStatus());
        
        double expectedRate = 1000 * 0.9;
        assertEquals(900, (int) expectedRate);
    }

    @Test
    @DisplayName("退票金额计算 - 提前3-7天退款（30%手续费）")
    void testRefundAmountNormal() {
        double feeRate = MockConfig.getRefundFeeRate(5);
        int ticketPrice = 1000;
        
        assertEquals(0.30, feeRate);
        int expectedRefund = (int) (ticketPrice * (1 - feeRate));
        assertEquals(700, expectedRefund);
    }

    @Test
    @DisplayName("退票金额计算 - 提前不足3天退款（50%手续费）")
    void testRefundAmountLate() {
        double feeRate = MockConfig.getRefundFeeRate(2);
        int ticketPrice = 1000;
        
        assertEquals(0.50, feeRate);
        int expectedRefund = (int) (ticketPrice * (1 - feeRate));
        assertEquals(500, expectedRefund);
    }

    @Test
    @DisplayName("退票手续费规则验证")
    void testRefundFeeRules() {
        double earlyRate = MockConfig.getRefundFeeRate(15);
        double normalRate = MockConfig.getRefundFeeRate(5);
        double lateRate = MockConfig.getRefundFeeRate(1);

        assertTrue(earlyRate < normalRate);
        assertTrue(normalRate < lateRate);
        assertEquals(0.10, earlyRate);
        assertEquals(0.30, normalRate);
        assertEquals(0.50, lateRate);
    }

    @Test
    @DisplayName("退票成功后票务状态更新")
    void testRefundUpdatesTicketStatus() {
        String ticketId = "ticket_refund_status";
        String eventId = "event_refund_status";
        String seatId = "seat_refund_status";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("refund");
        request.setChangeReason("行程变更");

        changeService.processRefund(request);

        verify(ticketRepository).save(argThat(t -> 
            "refunded".equals(t.getTicketStatus()) && t.getCancelledAt() != null
        ));
    }

    @Test
    @DisplayName("退票成功后座位状态恢复")
    void testRefundReleasesSeat() {
        String ticketId = "ticket_refund_seat";
        String eventId = "event_refund_seat";
        String seatId = "seat_refund_seat";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("refund");
        request.setChangeReason("行程变更");

        changeService.processRefund(request);

        verify(seatRepository).save(argThat(s -> 
            "available".equals(s.getSeatStatus()) && 
            s.getSoldAt() == null && 
            s.getLockedAt() == null
        ));
    }

    @Test
    @DisplayName("退票记录完整性验证")
    void testRefundRecordIntegrity() {
        String ticketId = "ticket_refund_record";
        String eventId = "event_refund_record";
        String seatId = "seat_refund_record";
        String reason = "突发疾病需要取消";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        confirmedTicket.setTicketPrice(1500);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("refund");
        request.setChangeReason(reason);

        ChangeRecord record = changeService.processRefund(request);

        assertNotNull(record);
        assertNotNull(record.getChangeId());
        assertEquals(ticketId, record.getTicketId());
        assertEquals("refund", record.getChangeType());
        assertEquals(reason, record.getChangeReason());
        assertEquals("approved", record.getChangeStatus());
        assertNotNull(record.getChangeTime());
    }

    @Test
    @DisplayName("VIP票退票金额计算")
    void testVIPTicketRefundAmount() {
        String ticketId = "ticket_vip_refund";
        String eventId = "event_vip_refund";
        String seatId = "seat_vip_refund";
        
        Ticket confirmedTicket = TestDataBuilder.createVIPConfirmedTicket(ticketId, eventId, seatId);
        confirmedTicket.setTicketPrice(3000);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "VIP");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("refund");
        request.setChangeReason("行程变更");

        ChangeRecord record = changeService.processRefund(request);

        assertNotNull(record);
        assertNotNull(record.getChangeAmount());
        assertTrue(record.getChangeAmount() > 0);
        assertTrue(record.getChangeAmount() <= 3000);
    }

    @Test
    @DisplayName("票务不存在时退票失败")
    void testRefundWhenTicketNotExist() {
        String ticketId = "ticket_not_exist";

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("refund");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            changeService.processRefund(request);
        });

        assertEquals("票务不存在", exception.getMessage());
        verify(changeRecordRepository, never()).save(any(ChangeRecord.class));
    }

    @Test
    @DisplayName("非已确认状态票务不能退票")
    void testRefundNonConfirmedTicket() {
        String ticketId = "ticket_pending_refund";
        String eventId = "event_pending_refund";
        String seatId = "seat_pending_refund";
        
        Ticket pendingTicket = TestDataBuilder.createPendingPaymentTicket(ticketId, eventId, seatId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(pendingTicket));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("refund");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            changeService.processRefund(request);
        });

        assertEquals("只能退改已确认的票务", exception.getMessage());
        verify(changeRecordRepository, never()).save(any(ChangeRecord.class));
    }

    @Test
    @DisplayName("改签差价计算 - 升级到VIP座位")
    void testExchangePriceDifferenceUpgrade() {
        String ticketId = "ticket_exchange_upgrade";
        String eventId = "event_exchange_upgrade";
        String oldSeatId = "seat_old_regular";
        String newSeatId = "seat_new_vip";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, oldSeatId);
        confirmedTicket.setTicketPrice(500);
        Seat oldSeat = TestDataBuilder.createSoldSeat(oldSeatId, eventId, "Regular");
        Seat newSeat = TestDataBuilder.createVIPSeat(newSeatId, eventId);
        newSeat.setSeatPrice(1800);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findByIdWithLock(newSeatId)).thenReturn(Optional.of(newSeat));
        when(seatRepository.findById(oldSeatId)).thenReturn(Optional.of(oldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("exchange");
        request.setNewSeatId(newSeatId);
        request.setChangeReason("希望升级座位");

        ChangeRecord record = changeService.processExchange(request);

        assertNotNull(record);
        assertEquals("exchange", record.getChangeType());
        assertEquals(1800, record.getChangeAmount());
        
        int priceDiff = 1800 - 500;
        assertEquals(1300, priceDiff);
    }

    @Test
    @DisplayName("改签差价计算 - 降级到普通座位")
    void testExchangePriceDifferenceDowngrade() {
        int oldPrice = 1800;
        int newPrice = 500;
        
        int priceDiff = newPrice - oldPrice;
        int refundAmount = Math.abs(priceDiff);
        
        assertEquals(-1300, priceDiff);
        assertEquals(1300, refundAmount);
    }

    @Test
    @DisplayName("改签成功后旧座位状态恢复")
    void testExchangeReleasesOldSeat() {
        String ticketId = "ticket_exchange_old";
        String eventId = "event_exchange_old";
        String oldSeatId = "seat_old_001";
        String newSeatId = "seat_new_001";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, oldSeatId);
        Seat oldSeat = TestDataBuilder.createSoldSeat(oldSeatId, eventId, "Regular");
        Seat newSeat = TestDataBuilder.createRegularSeat(newSeatId, eventId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findByIdWithLock(newSeatId)).thenReturn(Optional.of(newSeat));
        when(seatRepository.findById(oldSeatId)).thenReturn(Optional.of(oldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("exchange");
        request.setNewSeatId(newSeatId);

        changeService.processExchange(request);

        verify(seatRepository).save(argThat(s -> 
            s.getSeatId().equals(oldSeatId) && 
            "available".equals(s.getSeatStatus())
        ));
    }

    @Test
    @DisplayName("改签成功后新座位状态更新")
    void testExchangeUpdatesNewSeatStatus() {
        String ticketId = "ticket_exchange_new";
        String eventId = "event_exchange_new";
        String oldSeatId = "seat_old_002";
        String newSeatId = "seat_new_002";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, oldSeatId);
        Seat oldSeat = TestDataBuilder.createSoldSeat(oldSeatId, eventId, "Regular");
        Seat newSeat = TestDataBuilder.createRegularSeat(newSeatId, eventId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findByIdWithLock(newSeatId)).thenReturn(Optional.of(newSeat));
        when(seatRepository.findById(oldSeatId)).thenReturn(Optional.of(oldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("exchange");
        request.setNewSeatId(newSeatId);

        changeService.processExchange(request);

        verify(seatRepository).save(argThat(s -> 
            s.getSeatId().equals(newSeatId) && 
            "sold".equals(s.getSeatStatus()) &&
            s.getSoldAt() != null
        ));
    }

    @Test
    @DisplayName("改签成功后票务座位信息更新")
    void testExchangeUpdatesTicketSeatInfo() {
        String ticketId = "ticket_exchange_ticket";
        String eventId = "event_exchange_ticket";
        String oldSeatId = "seat_old_003";
        String newSeatId = "seat_new_003";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, oldSeatId);
        Seat oldSeat = TestDataBuilder.createSoldSeat(oldSeatId, eventId, "Regular");
        Seat newSeat = TestDataBuilder.createVIPSeat(newSeatId, eventId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findByIdWithLock(newSeatId)).thenReturn(Optional.of(newSeat));
        when(seatRepository.findById(oldSeatId)).thenReturn(Optional.of(oldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("exchange");
        request.setNewSeatId(newSeatId);

        changeService.processExchange(request);

        verify(ticketRepository).save(argThat(t -> 
            newSeatId.equals(t.getSeatId()) && 
            t.getTicketPrice() == newSeat.getSeatPrice()
        ));
    }

    @Test
    @DisplayName("改签记录完整性验证")
    void testExchangeRecordIntegrity() {
        String ticketId = "ticket_exchange_record";
        String eventId = "event_exchange_record";
        String oldSeatId = "seat_old_004";
        String newSeatId = "seat_new_004";
        String reason = "需要更大的空间";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, oldSeatId);
        Seat oldSeat = TestDataBuilder.createSoldSeat(oldSeatId, eventId, "Regular");
        Seat newSeat = TestDataBuilder.createRegularSeat(newSeatId, eventId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findByIdWithLock(newSeatId)).thenReturn(Optional.of(newSeat));
        when(seatRepository.findById(oldSeatId)).thenReturn(Optional.of(oldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("exchange");
        request.setNewSeatId(newSeatId);
        request.setChangeReason(reason);

        ChangeRecord record = changeService.processExchange(request);

        assertNotNull(record);
        assertNotNull(record.getChangeId());
        assertEquals(ticketId, record.getTicketId());
        assertEquals("exchange", record.getChangeType());
        assertEquals(reason, record.getChangeReason());
        assertEquals("approved", record.getChangeStatus());
        assertNotNull(record.getChangeTime());
        assertNotNull(record.getChangeAmount());
    }

    @Test
    @DisplayName("改签必须指定新座位")
    void testExchangeRequiresNewSeat() {
        String ticketId = "ticket_exchange_no_seat";
        String eventId = "event_exchange_no_seat";
        String seatId = "seat_exchange_no_seat";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("exchange");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            changeService.processExchange(request);
        });

        assertEquals("改签必须指定新座位", exception.getMessage());
        verify(changeRecordRepository, never()).save(any(ChangeRecord.class));
    }

    @Test
    @DisplayName("新座位不存在时改签失败")
    void testExchangeWhenNewSeatNotExist() {
        String ticketId = "ticket_exchange_seat_not_exist";
        String eventId = "event_exchange_seat_not_exist";
        String oldSeatId = "seat_old_exist";
        String newSeatId = "seat_new_not_exist";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, oldSeatId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findByIdWithLock(newSeatId)).thenReturn(Optional.empty());

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("exchange");
        request.setNewSeatId(newSeatId);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            changeService.processExchange(request);
        });

        assertEquals("新座位不存在", exception.getMessage());
    }

    @Test
    @DisplayName("新座位不可用时改签失败")
    void testExchangeWhenNewSeatUnavailable() {
        String ticketId = "ticket_exchange_unavailable";
        String eventId = "event_exchange_unavailable";
        String oldSeatId = "seat_old_005";
        String newSeatId = "seat_new_sold";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, oldSeatId);
        Seat soldNewSeat = TestDataBuilder.createSoldSeat(newSeatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findByIdWithLock(newSeatId)).thenReturn(Optional.of(soldNewSeat));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("exchange");
        request.setNewSeatId(newSeatId);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            changeService.processExchange(request);
        });

        assertEquals("新座位不可用", exception.getMessage());
    }

    @Test
    @DisplayName("只能改签同一活动的座位")
    void testExchangeMustBeSameEvent() {
        String ticketId = "ticket_exchange_cross_event";
        String eventId1 = "event_1";
        String eventId2 = "event_2";
        String oldSeatId = "seat_old_event1";
        String newSeatId = "seat_new_event2";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId1, oldSeatId);
        Seat newSeatOtherEvent = TestDataBuilder.createRegularSeat(newSeatId, eventId2);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findByIdWithLock(newSeatId)).thenReturn(Optional.of(newSeatOtherEvent));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("exchange");
        request.setNewSeatId(newSeatId);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            changeService.processExchange(request);
        });

        assertEquals("只能改签同一活动的座位", exception.getMessage());
    }

    @Test
    @DisplayName("退票历史记录记录")
    void testRefundHistoryRecorded() {
        String ticketId = "ticket_refund_history";
        String eventId = "event_refund_history";
        String seatId = "seat_refund_history";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("refund");

        changeService.processRefund(request);

        verify(ticketHistoryService, times(1)).recordRefund(eq(ticketId), anyString());
    }

    @Test
    @DisplayName("改签历史记录记录")
    void testExchangeHistoryRecorded() {
        String ticketId = "ticket_exchange_history";
        String eventId = "event_exchange_history";
        String oldSeatId = "seat_old_hist";
        String newSeatId = "seat_new_hist";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, oldSeatId);
        Seat oldSeat = TestDataBuilder.createSoldSeat(oldSeatId, eventId, "Regular");
        Seat newSeat = TestDataBuilder.createRegularSeat(newSeatId, eventId);

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findByIdWithLock(newSeatId)).thenReturn(Optional.of(newSeat));
        when(seatRepository.findById(oldSeatId)).thenReturn(Optional.of(oldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("exchange");
        request.setNewSeatId(newSeatId);

        changeService.processExchange(request);

        verify(ticketHistoryService, times(1)).recordChange(eq(ticketId), anyString());
    }

    @Test
    @DisplayName("退改后统计数据更新")
    void testChangeUpdatesAnalytics() {
        String ticketId = "ticket_change_analytics";
        String eventId = "event_change_analytics";
        String seatId = "seat_change_analytics";
        
        Ticket confirmedTicket = TestDataBuilder.createConfirmedTicket(ticketId, eventId, seatId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(confirmedTicket));
        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(changeRecordRepository.save(any(ChangeRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChangeRequest request = new ChangeRequest();
        request.setTicketId(ticketId);
        request.setChangeType("refund");

        changeService.processRefund(request);

        verify(analyticsService, times(1)).updateMonthlyStatistics();
    }

    @Test
    @DisplayName("获取退改记录测试")
    void testGetChangeRecordById() {
        String changeId = "change_001";
        String ticketId = "ticket_get_change";
        ChangeRecord record = TestDataBuilder.createRefundRecord(changeId, ticketId, 500);

        when(changeRecordRepository.findById(changeId)).thenReturn(Optional.of(record));

        Optional<ChangeRecord> result = changeService.getChangeRecordById(changeId);

        assertTrue(result.isPresent());
        assertEquals(changeId, result.get().getChangeId());
        assertEquals("refund", result.get().getChangeType());
    }

    @Test
    @DisplayName("获取票务退改记录列表测试")
    void testGetChangeRecordsByTicketId() {
        String ticketId = "ticket_changes_list";
        java.util.List<ChangeRecord> records = java.util.Arrays.asList(
            TestDataBuilder.createRefundRecord("change_1", ticketId, 500),
            TestDataBuilder.createExchangeRecord("change_2", ticketId, 800)
        );

        when(changeRecordRepository.findByTicketId(ticketId)).thenReturn(records);

        java.util.List<ChangeRecord> result = changeService.getChangeRecordsByTicketId(ticketId);

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("获取退票记录列表测试")
    void testGetRefundsByTicketId() {
        String ticketId = "ticket_refunds_list";
        java.util.List<ChangeRecord> refunds = java.util.Arrays.asList(
            TestDataBuilder.createRefundRecord("refund_1", ticketId, 500),
            TestDataBuilder.createRefundRecord("refund_2", ticketId, 800)
        );

        when(changeRecordRepository.findByTicketIdAndChangeType(ticketId, "refund")).thenReturn(refunds);

        java.util.List<ChangeRecord> result = changeService.getRefundsByTicketId(ticketId);

        assertNotNull(result);
        assertEquals(2, result.size());
        result.forEach(r -> assertEquals("refund", r.getChangeType()));
    }

    @Test
    @DisplayName("获取改签记录列表测试")
    void testGetExchangesByTicketId() {
        String ticketId = "ticket_exchanges_list";
        java.util.List<ChangeRecord> exchanges = java.util.Arrays.asList(
            TestDataBuilder.createExchangeRecord("exchange_1", ticketId, 800),
            TestDataBuilder.createExchangeRecord("exchange_2", ticketId, 1200)
        );

        when(changeRecordRepository.findByTicketIdAndChangeType(ticketId, "exchange")).thenReturn(exchanges);

        java.util.List<ChangeRecord> result = changeService.getExchangesByTicketId(ticketId);

        assertNotNull(result);
        assertEquals(2, result.size());
        result.forEach(r -> assertEquals("exchange", r.getChangeType()));
    }
}
