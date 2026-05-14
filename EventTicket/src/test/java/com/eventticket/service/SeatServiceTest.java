package com.eventticket.service;

import com.eventticket.builder.MockConfig;
import com.eventticket.builder.TestDataBuilder;
import com.eventticket.dto.SeatAssignRequest;
import com.eventticket.entity.Event;
import com.eventticket.entity.Seat;
import com.eventticket.repository.EventRepository;
import com.eventticket.repository.SeatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("座位模块单元测试")
class SeatServiceTest {

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private SeatService seatService;

    @Test
    @DisplayName("座位分区配置加载正确性测试 - VIP区")
    void testVIPSectionSeatLoading() {
        String eventId = "event_vip_test";
        String seatId = "seat_vip_001";
        Seat vipSeat = TestDataBuilder.createVIPSeat(seatId, eventId);

        when(seatRepository.findById(seatId)).thenReturn(Optional.of(vipSeat));

        Optional<Seat> result = seatService.getSeatById(seatId);

        assertTrue(result.isPresent());
        assertEquals("VIP", result.get().getSeatSection());
        assertEquals(1800, result.get().getSeatPrice());
        assertEquals("V001", result.get().getSeatNumber());
        verify(seatRepository, times(1)).findById(seatId);
    }

    @Test
    @DisplayName("座位分区配置加载正确性测试 - 普通区")
    void testRegularSectionSeatLoading() {
        String eventId = "event_reg_test";
        String seatId = "seat_reg_001";
        Seat regularSeat = TestDataBuilder.createRegularSeat(seatId, eventId);

        when(seatRepository.findById(seatId)).thenReturn(Optional.of(regularSeat));

        Optional<Seat> result = seatService.getSeatById(seatId);

        assertTrue(result.isPresent());
        assertEquals("Regular", result.get().getSeatSection());
        assertEquals(500, result.get().getSeatPrice());
        assertEquals("A101", result.get().getSeatNumber());
        verify(seatRepository, times(1)).findById(seatId);
    }

    @Test
    @DisplayName("手动分配座位正确性测试")
    void testManualSeatAssignment() {
        String eventId = "event_manual_test";
        String seatId = "seat_manual_001";
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat availableSeat = TestDataBuilder.createRegularSeat(seatId, eventId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.of(availableSeat));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SeatAssignRequest request = new SeatAssignRequest();
        request.setEventId(eventId);
        request.setSeatId(seatId);

        Seat assignedSeat = seatService.assignSeat(request);

        assertNotNull(assignedSeat);
        assertEquals("locked", assignedSeat.getSeatStatus());
        assertNotNull(assignedSeat.getLockedAt());
        verify(seatRepository, times(1)).findByIdWithLock(seatId);
        verify(seatRepository, times(1)).save(any(Seat.class));
    }

    @Test
    @DisplayName("自动分配座位正确性测试")
    void testAutomaticSeatAssignment() {
        String eventId = "event_auto_test";
        Event event = TestDataBuilder.createConcertEvent(eventId);
        List<Seat> availableSeats = TestDataBuilder.createMultipleSeats(eventId, 5, "Regular");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findAvailableSeatsSorted(eventId)).thenReturn(availableSeats);
        when(seatRepository.findByIdWithLock(availableSeats.get(0).getSeatId()))
            .thenReturn(Optional.of(availableSeats.get(0)));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SeatAssignRequest request = new SeatAssignRequest();
        request.setEventId(eventId);

        Seat assignedSeat = seatService.assignSeat(request);

        assertNotNull(assignedSeat);
        assertEquals("locked", assignedSeat.getSeatStatus());
        assertEquals(availableSeats.get(0).getSeatNumber(), assignedSeat.getSeatNumber());
        verify(seatRepository, times(1)).findAvailableSeatsSorted(eventId);
    }

    @Test
    @DisplayName("手动分配已售出座位时抛出异常")
    void testManualAssignSoldSeatThrowsException() {
        String eventId = "event_sold_test";
        String seatId = "seat_sold_001";
        Event event = TestDataBuilder.createConcertEvent(eventId);
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.of(soldSeat));

        SeatAssignRequest request = new SeatAssignRequest();
        request.setEventId(eventId);
        request.setSeatId(seatId);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            seatService.assignSeat(request);
        });

        assertEquals("座位不可用", exception.getMessage());
        verify(seatRepository, never()).save(any(Seat.class));
    }

    @Test
    @DisplayName("活动不存在时分配座位抛出异常")
    void testAssignSeatWhenEventNotExistThrowsException() {
        String eventId = "event_not_exist";

        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        SeatAssignRequest request = new SeatAssignRequest();
        request.setEventId(eventId);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            seatService.assignSeat(request);
        });

        assertEquals("活动不存在", exception.getMessage());
        verify(seatRepository, never()).save(any(Seat.class));
    }

    @Test
    @DisplayName("座位状态变更 - 空闲到锁定")
    void testSeatStatusChangeAvailableToLocked() {
        String seatId = "seat_status_1";
        String eventId = "event_status_1";
        Seat seat = TestDataBuilder.createRegularSeat(seatId, eventId);

        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.of(seat));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Seat updatedSeat = seatService.updateSeatStatus(seatId, "locked");

        assertNotNull(updatedSeat);
        assertEquals("locked", updatedSeat.getSeatStatus());
        assertNull(updatedSeat.getSoldAt());
        assertNull(updatedSeat.getAdmittedAt());
    }

    @Test
    @DisplayName("座位状态变更 - 锁定到已售")
    void testSeatStatusChangeLockedToSold() {
        String seatId = "seat_status_2";
        String eventId = "event_status_2";
        Seat seat = TestDataBuilder.createLockedSeat(seatId, eventId, "Regular");

        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.of(seat));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Seat updatedSeat = seatService.updateSeatStatus(seatId, "sold");

        assertNotNull(updatedSeat);
        assertEquals("sold", updatedSeat.getSeatStatus());
        assertNotNull(updatedSeat.getSoldAt());
        assertNull(updatedSeat.getAdmittedAt());
    }

    @Test
    @DisplayName("座位状态变更 - 已售到已入场")
    void testSeatStatusChangeSoldToAdmitted() {
        String seatId = "seat_status_3";
        String eventId = "event_status_3";
        Seat seat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.of(seat));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Seat updatedSeat = seatService.updateSeatStatus(seatId, "admitted");

        assertNotNull(updatedSeat);
        assertEquals("admitted", updatedSeat.getSeatStatus());
        assertNotNull(updatedSeat.getSoldAt());
        assertNotNull(updatedSeat.getAdmittedAt());
    }

    @Test
    @DisplayName("座位状态变更 - 已入场恢复到空闲")
    void testSeatStatusChangeAdmittedToAvailable() {
        String seatId = "seat_status_4";
        String eventId = "event_status_4";
        Seat seat = TestDataBuilder.createAdmittedSeat(seatId, eventId, "Regular");

        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.of(seat));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Seat updatedSeat = seatService.updateSeatStatus(seatId, "available");

        assertNotNull(updatedSeat);
        assertEquals("available", updatedSeat.getSeatStatus());
        assertNull(updatedSeat.getLockedAt());
        assertNull(updatedSeat.getSoldAt());
        assertNull(updatedSeat.getAdmittedAt());
    }

    @Test
    @DisplayName("释放已锁定座位")
    void testReleaseLockedSeat() {
        String seatId = "seat_release_1";
        String eventId = "event_release_1";
        Seat lockedSeat = TestDataBuilder.createLockedSeat(seatId, eventId, "Regular");

        when(seatRepository.findById(seatId)).thenReturn(Optional.of(lockedSeat));
        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        seatService.releaseSeat(seatId);

        verify(seatRepository, times(1)).findById(seatId);
        verify(seatRepository, times(1)).save(any(Seat.class));
    }

    @Test
    @DisplayName("释放已售出座位不做操作")
    void testReleaseSoldSeatDoesNothing() {
        String seatId = "seat_release_2";
        String eventId = "event_release_2";
        Seat soldSeat = TestDataBuilder.createSoldSeat(seatId, eventId, "Regular");

        when(seatRepository.findById(seatId)).thenReturn(Optional.of(soldSeat));

        seatService.releaseSeat(seatId);

        verify(seatRepository, times(1)).findById(seatId);
        verify(seatRepository, never()).save(any(Seat.class));
    }

    @Test
    @DisplayName("获取活动可用座位列表 - VIP区")
    void testGetAvailableVIPSeats() {
        String eventId = "event_available_vip";
        List<Seat> vipSeats = TestDataBuilder.createMultipleSeats(eventId, 10, "VIP");

        when(seatRepository.findAvailableSeatsByEventIdAndSection(eventId, "VIP")).thenReturn(vipSeats);

        List<Seat> result = seatService.getAvailableSeatsByEventIdAndSection(eventId, "VIP");

        assertNotNull(result);
        assertEquals(10, result.size());
        result.forEach(seat -> {
            assertEquals("VIP", seat.getSeatSection());
            assertEquals(1800, seat.getSeatPrice());
        });
    }

    @Test
    @DisplayName("获取活动可用座位列表 - 普通区")
    void testGetAvailableRegularSeats() {
        String eventId = "event_available_reg";
        List<Seat> regularSeats = TestDataBuilder.createMultipleSeats(eventId, 50, "Regular");

        when(seatRepository.findAvailableSeatsByEventId(eventId)).thenReturn(regularSeats);

        List<Seat> result = seatService.getAvailableSeatsByEventIdAndSection(eventId, null);

        assertNotNull(result);
        assertEquals(50, result.size());
        result.forEach(seat -> {
            assertEquals("Regular", seat.getSeatSection());
            assertEquals(500, seat.getSeatPrice());
        });
    }

    @Test
    @DisplayName("统计活动可用座位数量")
    void testCountAvailableSeats() {
        String eventId = "event_count_1";

        when(seatRepository.countAvailableSeats(eventId)).thenReturn(4500L);

        long count = seatService.countAvailableSeats(eventId);

        assertEquals(4500L, count);
        verify(seatRepository, times(1)).countAvailableSeats(eventId);
    }

    @Test
    @DisplayName("按状态统计座位数量 - 已售出")
    void testCountSeatsByStatusSold() {
        String eventId = "event_count_2";

        when(seatRepository.countByEventIdAndSeatStatus(eventId, "sold")).thenReturn(500L);

        long count = seatService.countSeatsByStatus(eventId, "sold");

        assertEquals(500L, count);
        verify(seatRepository, times(1)).countByEventIdAndSeatStatus(eventId, "sold");
    }

    @Test
    @DisplayName("按状态统计座位数量 - 已入场")
    void testCountSeatsByStatusAdmitted() {
        String eventId = "event_count_3";

        when(seatRepository.countByEventIdAndSeatStatus(eventId, "admitted")).thenReturn(400L);

        long count = seatService.countSeatsByStatus(eventId, "admitted");

        assertEquals(400L, count);
        verify(seatRepository, times(1)).countByEventIdAndSeatStatus(eventId, "admitted");
    }

    @Test
    @DisplayName("创建座位")
    void testCreateSeat() {
        String eventId = "event_create_1";
        Seat seat = TestDataBuilder.createRegularSeat(null, eventId);

        when(seatRepository.save(any(Seat.class))).thenAnswer(invocation -> {
            Seat saved = invocation.getArgument(0);
            if (saved.getSeatId() == null) {
                saved.setSeatId(TestDataBuilder.generateId("seat"));
            }
            return saved;
        });

        Seat created = seatService.createSeat(seat);

        assertNotNull(created);
        assertNotNull(created.getSeatId());
        assertNotNull(created.getCreatedAt());
        assertEquals("available", created.getSeatStatus());
    }

    @Test
    @DisplayName("获取活动所有座位")
    void testGetSeatsByEventId() {
        String eventId = "event_all_seats";
        List<Seat> seats = Arrays.asList(
            TestDataBuilder.createVIPSeat("seat_vip_1", eventId),
            TestDataBuilder.createRegularSeat("seat_reg_1", eventId)
        );

        when(seatRepository.findByEventId(eventId)).thenReturn(seats);

        List<Seat> result = seatService.getSeatsByEventId(eventId);

        assertNotNull(result);
        assertEquals(2, result.size());
        verify(seatRepository, times(1)).findByEventId(eventId);
    }

    @Test
    @DisplayName("座位不存在时更新状态返回null")
    void testUpdateSeatStatusWhenSeatNotExist() {
        String seatId = "seat_not_exist";

        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.empty());

        Seat result = seatService.updateSeatStatus(seatId, "sold");

        assertNull(result);
        verify(seatRepository, never()).save(any(Seat.class));
    }

    @Test
    @DisplayName("自动分配时无可用座位抛出异常")
    void testAutomaticAssignWhenNoAvailableSeatsThrowsException() {
        String eventId = "event_no_seats";
        Event event = TestDataBuilder.createConcertEvent(eventId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(seatRepository.findAvailableSeatsSorted(eventId)).thenReturn(List.of());

        SeatAssignRequest request = new SeatAssignRequest();
        request.setEventId(eventId);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            seatService.assignSeat(request);
        });

        assertEquals("没有可用座位", exception.getMessage());
    }

    @Test
    @DisplayName("座位不属于活动时手动分配抛出异常")
    void testManualAssignSeatNotBelongToEvent() {
        String eventId1 = "event_1";
        String eventId2 = "event_2";
        String seatId = "seat_cross_event";
        Event event = TestDataBuilder.createConcertEvent(eventId1);
        Seat seat = TestDataBuilder.createRegularSeat(seatId, eventId2);

        when(eventRepository.findById(eventId1)).thenReturn(Optional.of(event));
        when(seatRepository.findByIdWithLock(seatId)).thenReturn(Optional.of(seat));

        SeatAssignRequest request = new SeatAssignRequest();
        request.setEventId(eventId1);
        request.setSeatId(seatId);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            seatService.assignSeat(request);
        });

        assertEquals("座位不属于该活动", exception.getMessage());
    }
}
