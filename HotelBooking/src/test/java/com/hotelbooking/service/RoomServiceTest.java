package com.hotelbooking.service;

import com.hotelbooking.builder.TestDataBuilder;
import com.hotelbooking.model.Booking;
import com.hotelbooking.model.Hotel;
import com.hotelbooking.model.Room;
import com.hotelbooking.repository.BookingRepository;
import com.hotelbooking.repository.HotelRepository;
import com.hotelbooking.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private HotelRepository hotelRepository;

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private RoomService roomService;

    private TestDataBuilder testDataBuilder;
    private Hotel testHotel;
    private Room testRoom;
    private LocalDate today;
    private LocalDate tomorrow;
    private LocalDate nextWeek;

    @BeforeEach
    void setUp() {
        testDataBuilder = new TestDataBuilder();
        testHotel = testDataBuilder.buildActiveHotel();
        testRoom = testDataBuilder.buildAvailableRoom(testHotel);
        today = testDataBuilder.today();
        tomorrow = testDataBuilder.tomorrow();
        nextWeek = testDataBuilder.daysFromNow(7);

        when(hotelRepository.findById(testHotel.getHotelId())).thenReturn(Optional.of(testHotel));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("测试房间状态流转 - 空闲->已预订->已入住->空闲")
    void testRoomStateTransition_FullCycle_ShouldTransitionCorrectly() {
        when(roomRepository.findByIdForUpdate(testRoom.getRoomId())).thenReturn(Optional.of(testRoom));

        Room available = roomService.updateRoomStatus(testRoom.getRoomId(), "available");
        assertEquals("available", available.getRoomStatus());

        Room booked = roomService.updateRoomStatus(testRoom.getRoomId(), "booked");
        assertEquals("booked", booked.getRoomStatus());

        Room occupied = roomService.updateRoomStatus(testRoom.getRoomId(), "occupied");
        assertEquals("occupied", occupied.getRoomStatus());

        Room backToAvailable = roomService.updateRoomStatus(testRoom.getRoomId(), "available");
        assertEquals("available", backToAvailable.getRoomStatus());

        verify(roomRepository, times(4)).findByIdForUpdate(testRoom.getRoomId());
        verify(roomRepository, times(4)).save(any(Room.class));
    }

    @Test
    @DisplayName("测试房间状态变更的正确性 - 状态更新后持久化")
    void testRoomStatusChange_ShouldPersistCorrectly() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        room.setRoomStatus("available");
        
        when(roomRepository.findByIdForUpdate(room.getRoomId())).thenReturn(Optional.of(room));

        Room updated = roomService.updateRoomStatus(room.getRoomId(), "booked");

        assertEquals("booked", updated.getRoomStatus());
        verify(roomRepository).save(argThat(r -> "booked".equals(r.getRoomStatus())));
    }

    @Test
    @DisplayName("测试房间状态变更 - 房间不存在应失败")
    void testRoomStatusChange_RoomNotFound_ShouldFail() {
        String nonExistentRoomId = "room_nonexistent";
        when(roomRepository.findByIdForUpdate(nonExistentRoomId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> roomService.updateRoomStatus(nonExistentRoomId, "booked"));
        
        assertTrue(exception.getMessage().contains("房间不存在"));
    }

    @Test
    @DisplayName("测试并发状态更新的安全性 - 乐观锁冲突处理")
    void testConcurrentStatusUpdate_WithOptimisticLocking_ShouldHandleConflicts() throws InterruptedException {
        int threadCount = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        room.setRoomId("room_test_001");
        room.setVersion(1L);

        when(roomRepository.findByIdForUpdate(room.getRoomId()))
                .thenAnswer(invocation -> {
                    if (successCount.get() > 0) {
                        room.setVersion(room.getVersion() + 1);
                    }
                    return Optional.of(room);
                });

        when(roomRepository.save(any(Room.class)))
                .thenAnswer(invocation -> {
                    Room saved = invocation.getArgument(0);
                    if (successCount.compareAndSet(0, 1)) {
                        return saved;
                    }
                    throw new RuntimeException("乐观锁冲突：版本已变更");
                });

        for (int i = 0; i < threadCount; i++) {
            final String status = (i % 2 == 0) ? "booked" : "available";
            executor.submit(() -> {
                try {
                    roomService.updateRoomStatus(room.getRoomId(), status);
                    successCount.incrementAndGet();
                } catch (RuntimeException e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertTrue(successCount.get() >= 1);
        assertEquals(threadCount, successCount.get() + failCount.get());
    }

    @Test
    @DisplayName("测试房间可用性检查 - 空闲房间应返回true")
    void testRoomAvailability_AvailableRoom_ShouldReturnTrue() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        when(bookingRepository.findConflictingBookings(eq(room.getRoomId()), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());

        boolean available = roomService.isRoomAvailable(room.getRoomId(), tomorrow, nextWeek);

        assertTrue(available);
    }

    @Test
    @DisplayName("测试房间可用性检查 - 有冲突预订应返回false")
    void testRoomAvailability_WithConflicts_ShouldReturnFalse() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        Booking conflict = testDataBuilder.buildConfirmedBooking(testHotel, room, tomorrow, nextWeek);
        
        when(bookingRepository.findConflictingBookings(eq(room.getRoomId()), eq(tomorrow), eq(nextWeek)))
                .thenReturn(List.of(conflict));

        boolean available = roomService.isRoomAvailable(room.getRoomId(), tomorrow, nextWeek);

        assertFalse(available);
    }

    @Test
    @DisplayName("测试房间可用性检查 - 入住日期等于退房日期应返回false")
    void testRoomAvailability_SameDate_ShouldReturnFalse() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);

        boolean available = roomService.isRoomAvailable(room.getRoomId(), tomorrow, tomorrow);

        assertFalse(available);
    }

    @Test
    @DisplayName("测试房间可用性检查 - 入住日期晚于退房日期应返回false")
    void testRoomAvailability_CheckInAfterCheckOut_ShouldReturnFalse() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);

        boolean available = roomService.isRoomAvailable(room.getRoomId(), nextWeek, tomorrow);

        assertFalse(available);
    }

    @Test
    @DisplayName("测试搜索可用房间 - 酒店关闭应失败")
    void testSearchAvailableRooms_HotelClosed_ShouldFail() {
        Hotel inactiveHotel = testDataBuilder.buildInactiveHotel();
        when(hotelRepository.findById(inactiveHotel.getHotelId())).thenReturn(Optional.of(inactiveHotel));

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> roomService.searchAvailableRooms(inactiveHotel.getHotelId(), tomorrow, nextWeek));
        
        assertTrue(exception.getMessage().contains("已关闭"));
    }

    @Test
    @DisplayName("测试搜索可用房间 - 酒店不存在应失败")
    void testSearchAvailableRooms_HotelNotFound_ShouldFail() {
        String nonExistentHotelId = "hotel_nonexistent";
        when(hotelRepository.findById(nonExistentHotelId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> roomService.searchAvailableRooms(nonExistentHotelId, tomorrow, nextWeek));
        
        assertTrue(exception.getMessage().contains("酒店不存在"));
    }

    @Test
    @DisplayName("测试创建房间 - 成功创建")
    void testCreateRoom_Successful_ShouldCreateRoom() {
        Room room = new Room();
        room.setRoomNumber("201");
        room.setRoomType("deluxe");
        room.setRoomPrice(500.0);

        Room created = roomService.createRoom(room, testHotel.getHotelId());

        assertNotNull(created.getRoomId());
        assertEquals("201", created.getRoomNumber());
        assertEquals("deluxe", created.getRoomType());
        assertEquals(500.0, created.getRoomPrice());
        assertEquals("available", created.getRoomStatus());
        assertNotNull(created.getCreatedAt());
    }

    @Test
    @DisplayName("测试创建房间 - 酒店不存在应失败")
    void testCreateRoom_HotelNotFound_ShouldFail() {
        String nonExistentHotelId = "hotel_nonexistent";
        when(hotelRepository.findById(nonExistentHotelId)).thenReturn(Optional.empty());

        Room room = new Room();
        room.setRoomNumber("301");

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> roomService.createRoom(room, nonExistentHotelId));
        
        assertTrue(exception.getMessage().contains("酒店不存在"));
    }

    @Test
    @DisplayName("测试获取房间信息 - 存在应返回")
    void testGetRoomById_ExistingRoom_ShouldReturn() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        when(roomRepository.findById(room.getRoomId())).thenReturn(Optional.of(room));

        Optional<Room> result = roomService.getRoomById(room.getRoomId());

        assertTrue(result.isPresent());
        assertEquals(room.getRoomId(), result.get().getRoomId());
    }

    @Test
    @DisplayName("测试获取房间信息 - 不存在应返回空")
    void testGetRoomById_NonExistingRoom_ShouldReturnEmpty() {
        String nonExistentRoomId = "room_nonexistent";
        when(roomRepository.findById(nonExistentRoomId)).thenReturn(Optional.empty());

        Optional<Room> result = roomService.getRoomById(nonExistentRoomId);

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("测试获取酒店所有房间 - 应返回列表")
    void testGetRoomsByHotel_ShouldReturnList() {
        Room room1 = testDataBuilder.buildAvailableRoom(testHotel);
        Room room2 = testDataBuilder.buildDeluxeRoom(testHotel);
        List<Room> rooms = List.of(room1, room2);

        when(roomRepository.findByHotelId(testHotel.getHotelId())).thenReturn(rooms);

        List<Room> result = roomService.getRoomsByHotel(testHotel.getHotelId());

        assertEquals(2, result.size());
        verify(roomRepository, times(1)).findByHotelId(testHotel.getHotelId());
    }

    @Test
    @DisplayName("测试更新房间信息 - 成功更新")
    void testUpdateRoom_Successful_ShouldUpdate() {
        Room existingRoom = testDataBuilder.buildAvailableRoom(testHotel);
        Room updates = new Room();
        updates.setRoomPrice(400.0);
        updates.setRoomFeatures(List.of("空调", "电视", "WiFi", "迷你吧"));

        when(roomRepository.findById(existingRoom.getRoomId())).thenReturn(Optional.of(existingRoom));

        Room updated = roomService.updateRoom(existingRoom.getRoomId(), updates);

        assertEquals(400.0, updated.getRoomPrice());
        assertTrue(updated.getRoomFeatures().contains("迷你吧"));
        verify(roomRepository, times(1)).save(existingRoom);
    }

    @Test
    @DisplayName("测试获取可用房间 - 应返回空闲房间列表")
    void testGetAvailableRooms_ShouldReturnAvailableRooms() {
        Room availableRoom = testDataBuilder.buildAvailableRoom(testHotel);
        when(roomRepository.findAvailableRoomsByHotelId(testHotel.getHotelId()))
                .thenReturn(List.of(availableRoom));

        List<Room> result = roomService.getAvailableRooms(testHotel.getHotelId());

        assertEquals(1, result.size());
        assertEquals("available", result.get(0).getRoomStatus());
    }

    @Test
    @DisplayName("测试获取按类型房间 - 应返回指定类型房间")
    void testGetRoomsByType_ShouldReturnRoomsOfType() {
        Room deluxeRoom = testDataBuilder.buildDeluxeRoom(testHotel);
        when(roomRepository.findByHotelIdAndRoomType(testHotel.getHotelId(), "deluxe"))
                .thenReturn(List.of(deluxeRoom));

        List<Room> result = roomService.getRoomsByType(testHotel.getHotelId(), "deluxe");

        assertEquals(1, result.size());
        assertEquals("deluxe", result.get(0).getRoomType());
    }

    @Test
    @DisplayName("测试房间状态流转序列 - 完整生命周期")
    void testRoomLifecycle_CompleteFlow_ShouldWorkCorrectly() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        
        when(roomRepository.findByIdForUpdate(room.getRoomId())).thenReturn(Optional.of(room));
        when(bookingRepository.findConflictingBookings(eq(room.getRoomId()), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());

        assertEquals("available", room.getRoomStatus());
        assertTrue(roomService.isRoomAvailable(room.getRoomId(), tomorrow, nextWeek));

        Room booked = roomService.updateRoomStatus(room.getRoomId(), "booked");
        assertEquals("booked", booked.getRoomStatus());

        Room occupied = roomService.updateRoomStatus(room.getRoomId(), "occupied");
        assertEquals("occupied", occupied.getRoomStatus());

        Room backToAvailable = roomService.updateRoomStatus(room.getRoomId(), "available");
        assertEquals("available", backToAvailable.getRoomStatus());
    }

    @Test
    @DisplayName("测试房间状态并发更新 - 多次状态切换")
    void testMultipleStatusChanges_ShouldWorkCorrectly() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        when(roomRepository.findByIdForUpdate(room.getRoomId())).thenReturn(Optional.of(room));

        String[] statuses = {"available", "booked", "occupied", "available", "booked", "available"};

        for (int i = 0; i < statuses.length - 1; i++) {
            Room updated = roomService.updateRoomStatus(room.getRoomId(), statuses[i + 1]);
            assertEquals(statuses[i + 1], updated.getRoomStatus());
        }

        verify(roomRepository, times(statuses.length - 1)).save(any(Room.class));
    }
}
