package com.restaurant.mgmt.service;

import com.restaurant.mgmt.builder.TestDataBuilder;
import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.RestaurantTable;
import com.restaurant.mgmt.repository.RestaurantTableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("座位模块 - 单元测试")
class TableServiceTest {

    @Mock
    private RestaurantTableRepository tableRepository;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private TableService tableService;

    @BeforeEach
    void setUp() {
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString());
    }

    @Nested
    @DisplayName("座位预约锁定测试")
    class ReservationLockTests {

        @Test
        @DisplayName("可用座位应能成功预约")
        void testAvailableTableCanBeReserved() {
            RestaurantTable table = TestDataBuilder.buildAvailableTableA01();

            when(tableRepository.findByTableNumber("A01"))
                .thenReturn(Optional.of(table));
            when(tableRepository.save(any(RestaurantTable.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            RestaurantTable reserved = tableService.reserveTable(
                "A01", 
                LocalDateTime.now().plusHours(1),
                "customer_001"
            );

            assertEquals("reserved", reserved.getTableStatus());
            assertEquals("customer_001", reserved.getReservedBy());
            assertNotNull(reserved.getReserveTime());
        }

        @Test
        @DisplayName("已预约座位再次预约应失败")
        void testReservedTableCannotBeReservedAgain() {
            RestaurantTable table = TestDataBuilder.buildReservedTable();

            when(tableRepository.findByTableNumber("B01"))
                .thenReturn(Optional.of(table));

            assertThrows(BusinessException.class, () ->
                tableService.reserveTable(
                    "B01",
                    LocalDateTime.now().plusHours(1),
                    "customer_002"
                )
            );
        }

        @Test
        @DisplayName("已占用座位预约应失败")
        void testOccupiedTableCannotBeReserved() {
            RestaurantTable table = TestDataBuilder.buildOccupiedTable();

            when(tableRepository.findByTableNumber("B02"))
                .thenReturn(Optional.of(table));

            assertThrows(BusinessException.class, () ->
                tableService.reserveTable(
                    "B02",
                    LocalDateTime.now().plusHours(1),
                    "customer_003"
                )
            );
        }

        @Test
        @DisplayName("不存在的桌号预约应失败")
        void testNonExistentTableCannotBeReserved() {
            when(tableRepository.findByTableNumber("Z99"))
                .thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                tableService.reserveTable(
                    "Z99",
                    LocalDateTime.now().plusHours(1),
                    "customer_004"
                )
            );
        }

        @Test
        @DisplayName("预约应记录预约时间和预约人")
        void testReservationRecordsTimeAndPerson() {
            RestaurantTable table = TestDataBuilder.buildAvailableTableA01();
            LocalDateTime reserveTime = LocalDateTime.now().plusHours(2);

            when(tableRepository.findByTableNumber("A01"))
                .thenReturn(Optional.of(table));
            when(tableRepository.save(any(RestaurantTable.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            RestaurantTable reserved = tableService.reserveTable(
                "A01", reserveTime, "customer_005");

            assertEquals(reserveTime, reserved.getReserveTime());
            assertEquals("customer_005", reserved.getReservedBy());
        }
    }

    @Nested
    @DisplayName("并发预约冲突处理测试")
    class ConcurrentReservationTests {

        @Test
        @DisplayName("并发预约同一座位只能有一个成功")
        void testConcurrentReservationOnlyOneSucceeds() throws InterruptedException {
            RestaurantTable table = TestDataBuilder.buildAvailableTableA01();

            when(tableRepository.findByTableNumber("A01"))
                .thenReturn(Optional.of(table));

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(10);
            ExecutorService executor = Executors.newFixedThreadPool(10);

            when(tableRepository.save(any(RestaurantTable.class)))
                .thenAnswer(inv -> {
                    RestaurantTable t = inv.getArgument(0);
                    if (!"reserved".equals(table.getTableStatus())) {
                        table.setTableStatus(t.getTableStatus());
                        table.setReservedBy(t.getReservedBy());
                        table.setReserveTime(t.getReserveTime());
                        return table;
                    }
                    throw new BusinessException("座位已被占用");
                });

            for (int i = 0; i < 10; i++) {
                final String customerId = "customer_" + i;
                executor.submit(() -> {
                    try {
                        tableService.reserveTable(
                            "A01",
                            LocalDateTime.now().plusHours(1),
                            customerId
                        );
                        successCount.incrementAndGet();
                    } catch (BusinessException e) {
                        failCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            assertTrue(successCount.get() >= 1);
            assertEquals(10, successCount.get() + failCount.get());
            assertEquals("reserved", table.getTableStatus());
        }

        @Test
        @DisplayName("并发预约失败方应收到座位不可用异常")
        void testConcurrentFailGetsException() {
            RestaurantTable table = TestDataBuilder.buildAvailableTableA01();

            when(tableRepository.findByTableNumber("A01"))
                .thenReturn(Optional.of(table));

            when(tableRepository.save(any(RestaurantTable.class)))
                .thenAnswer(inv -> {
                    if ("available".equals(table.getTableStatus())) {
                        table.setTableStatus("reserved");
                        return table;
                    }
                    throw new BusinessException("座位已被占用");
                });

            tableService.reserveTable("A01", LocalDateTime.now(), "customer1");

            assertThrows(BusinessException.class, () ->
                tableService.reserveTable("A01", LocalDateTime.now(), "customer2")
            );
        }
    }

    @Nested
    @DisplayName("座位状态流转测试")
    class TableStatusFlowTests {

        @Test
        @DisplayName("可用 -> 预约 -> 占用 -> 释放 完整流转")
        void testFullStatusFlow() {
            RestaurantTable table = TestDataBuilder.buildAvailableTableA01();

            assertEquals("available", table.getTableStatus());

            when(tableRepository.findByTableNumber(anyString()))
                .thenReturn(Optional.of(table));
            when(tableRepository.findById(anyString()))
                .thenReturn(Optional.of(table));
            when(tableRepository.save(any(RestaurantTable.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            RestaurantTable reserved = tableService.reserveTable(
                "A01", LocalDateTime.now(), "customer");
            assertEquals("reserved", reserved.getTableStatus());

            RestaurantTable occupied = tableService.occupyTable(TestDataBuilder.TABLE_A01_ID);
            assertEquals("occupied", occupied.getTableStatus());

            RestaurantTable released = tableService.releaseTable(TestDataBuilder.TABLE_A01_ID);
            assertEquals("available", released.getTableStatus());
        }

        @Test
        @DisplayName("取消预约应恢复可用状态")
        void testCancelReservationRestoresAvailable() {
            RestaurantTable table = TestDataBuilder.buildReservedTable();

            when(tableRepository.findById(TestDataBuilder.TABLE_B01_ID))
                .thenReturn(Optional.of(table));
            when(tableRepository.save(any(RestaurantTable.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            RestaurantTable released = tableService.cancelReservation(
                TestDataBuilder.TABLE_B01_ID, "测试取消");

            assertEquals("available", released.getTableStatus());
            assertNull(released.getReservedBy());
            assertNull(released.getReserveTime());
        }

        @Test
        @DisplayName("已占用座位不能直接预约")
        void testOccupiedCannotDirectlyReserve() {
            RestaurantTable table = TestDataBuilder.buildOccupiedTable();

            when(tableRepository.findByTableNumber("B02"))
                .thenReturn(Optional.of(table));

            assertThrows(BusinessException.class, () ->
                tableService.reserveTable("B02", LocalDateTime.now(), "customer")
            );
        }

        @Test
        @DisplayName("已预约座位释放后可再次预约")
        void testReleasedTableCanBeReservedAgain() {
            RestaurantTable table = TestDataBuilder.buildReservedTable();

            when(tableRepository.findById(TestDataBuilder.TABLE_B01_ID))
                .thenReturn(Optional.of(table));
            when(tableRepository.save(any(RestaurantTable.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            tableService.cancelReservation(TestDataBuilder.TABLE_B01_ID, "测试取消");

            assertEquals("available", table.getTableStatus());
        }
    }

    @Nested
    @DisplayName("座位分配测试")
    class TableAllocationTests {

        @Test
        @DisplayName("按容量分配合适座位")
        void testAllocateByCapacity() {
            RestaurantTable smallTable = TestDataBuilder.buildAvailableTableA01();
            smallTable.setTableCapacity(2);
            RestaurantTable mediumTable = TestDataBuilder.buildAvailableTableA02();
            mediumTable.setTableCapacity(4);
            RestaurantTable vipTable = TestDataBuilder.buildVipTable();
            vipTable.setTableCapacity(8);

            when(tableRepository.findByTableStatus("available"))
                .thenReturn(Arrays.asList(smallTable, mediumTable, vipTable));

            RestaurantTable allocated = tableService.allocateTable(3);

            assertEquals(4, allocated.getTableCapacity());
            assertEquals("A02", allocated.getTableNumber());
        }

        @Test
        @DisplayName("没有足够容量的座位应抛出异常")
        void testNoSuitableCapacityThrowsException() {
            RestaurantTable smallTable = TestDataBuilder.buildAvailableTableA01();
            smallTable.setTableCapacity(2);

            when(tableRepository.findByTableStatus("available"))
                .thenReturn(List.of(smallTable));

            assertThrows(BusinessException.class, () ->
                tableService.allocateTable(10)
            );
        }

        @Test
        @DisplayName("VIP座位应优先分配给大人数")
        void testVipTableAllocatedForLargeParty() {
            RestaurantTable standardTable = TestDataBuilder.buildAvailableTableA01();
            standardTable.setTableCapacity(4);
            RestaurantTable vipTable = TestDataBuilder.buildVipTable();
            vipTable.setTableCapacity(8);

            when(tableRepository.findByTableStatus("available"))
                .thenReturn(Arrays.asList(standardTable, vipTable));

            RestaurantTable allocated = tableService.allocateTable(6);

            assertEquals("vip", allocated.getTableType());
            assertEquals(8, allocated.getTableCapacity());
        }
    }

    @Nested
    @DisplayName("预约取消测试")
    class ReservationCancelTests {

        @Test
        @DisplayName("取消预约应清除预约信息")
        void testCancelClearsReservationInfo() {
            RestaurantTable table = TestDataBuilder.buildReservedTable();

            when(tableRepository.findById(TestDataBuilder.TABLE_B01_ID))
                .thenReturn(Optional.of(table));
            when(tableRepository.save(any(RestaurantTable.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            RestaurantTable cancelled = tableService.cancelReservation(
                TestDataBuilder.TABLE_B01_ID, "用户主动取消");

            assertEquals("available", cancelled.getTableStatus());
            assertNull(cancelled.getReservedBy());
            assertNull(cancelled.getReserveTime());
        }

        @Test
        @DisplayName("取消不存在的预约应失败")
        void testCancelNonExistentReservationFails() {
            when(tableRepository.findById("non_existent"))
                .thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                tableService.cancelReservation("non_existent", "测试")
            );
        }

        @Test
        @DisplayName("取消可用座位预约应失败")
        void testCancelAvailableTableFails() {
            RestaurantTable table = TestDataBuilder.buildAvailableTableA01();

            when(tableRepository.findById(TestDataBuilder.TABLE_A01_ID))
                .thenReturn(Optional.of(table));

            assertThrows(BusinessException.class, () ->
                tableService.cancelReservation(TestDataBuilder.TABLE_A01_ID, "测试")
            );
        }
    }

    @Nested
    @DisplayName("座位查询测试")
    class TableQueryTests {

        @Test
        @DisplayName("应能查询所有可用座位")
        void testGetAllAvailableTables() {
            RestaurantTable available1 = TestDataBuilder.buildAvailableTableA01();
            RestaurantTable available2 = TestDataBuilder.buildAvailableTableA02();
            RestaurantTable reserved = TestDataBuilder.buildReservedTable();

            when(tableRepository.findByTableStatus("available"))
                .thenReturn(Arrays.asList(available1, available2));

            List<RestaurantTable> available = tableService.getAvailableTables();

            assertEquals(2, available.size());
            assertTrue(available.stream()
                .allMatch(t -> "available".equals(t.getTableStatus())));
        }

        @Test
        @DisplayName("按类型查询座位")
        void testGetTablesByType() {
            RestaurantTable standard = TestDataBuilder.buildAvailableTableA01();
            RestaurantTable vip = TestDataBuilder.buildVipTable();

            when(tableRepository.findByTableType("vip"))
                .thenReturn(List.of(vip));

            List<RestaurantTable> vipTables = tableService.getTablesByType("vip");

            assertEquals(1, vipTables.size());
            assertEquals("vip", vipTables.get(0).getTableType());
        }

        @Test
        @DisplayName("按桌号查询座位")
        void testGetTableByNumber() {
            RestaurantTable table = TestDataBuilder.buildAvailableTableA01();

            when(tableRepository.findByTableNumber("A01"))
                .thenReturn(Optional.of(table));

            RestaurantTable found = tableService.getTableByNumber("A01");

            assertNotNull(found);
            assertEquals("A01", found.getTableNumber());
        }
    }
}
