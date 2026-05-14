package com.flightmgmt.test.flight;

import com.flightmgmt.common.model.*;
import com.flightmgmt.common.util.DataStore;
import com.flightmgmt.flight.service.FlightService;
import com.flightmgmt.test.data.TestDataBuilder;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("航班管理模块单元测试")
public class FlightServiceTest {

    private FlightService flightService;

    @BeforeEach
    void setUp() {
        flightService = new FlightService();
        DataStore.getFlights().clear();
        DataStore.getBookings().clear();
        DataStore.getPassengers().clear();
        TestDataBuilder.resetCounters();
    }

    @Nested
    @DisplayName("航班状态流转测试")
    class FlightStatusFlowTests {

        @Test
        @DisplayName("验证航班初始状态为scheduled")
        void testInitialStatus_IsScheduled() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            assertEquals("scheduled", flight.getFlightStatus());
        }

        @Test
        @DisplayName("验证航班状态从scheduled变为delayed")
        void testStatusFlow_ScheduledToDelayed() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            assertEquals("scheduled", flight.getFlightStatus());

            Flight updated = flightService.updateFlightStatus(flight.getFlightId(), "delayed");

            assertEquals("delayed", updated.getFlightStatus());
        }

        @Test
        @DisplayName("验证航班状态从scheduled变为cancelled")
        void testStatusFlow_ScheduledToCancelled() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            assertEquals("scheduled", flight.getFlightStatus());

            Flight updated = flightService.updateFlightStatus(flight.getFlightId(), "cancelled");

            assertEquals("cancelled", updated.getFlightStatus());
        }

        @Test
        @DisplayName("验证航班状态从delayed变为on_time")
        void testStatusFlow_DelayedToOnTime() {
            Flight flight = TestDataBuilder.createFlightWithStatus("delayed");
            DataStore.addFlight(flight);

            assertEquals("delayed", flight.getFlightStatus());

            Flight updated = flightService.updateFlightStatus(flight.getFlightId(), "on_time");

            assertEquals("on_time", updated.getFlightStatus());
        }

        @Test
        @DisplayName("验证航班状态从delayed变为cancelled")
        void testStatusFlow_DelayedToCancelled() {
            Flight flight = TestDataBuilder.createFlightWithStatus("delayed");
            DataStore.addFlight(flight);

            Flight updated = flightService.updateFlightStatus(flight.getFlightId(), "cancelled");

            assertEquals("cancelled", updated.getFlightStatus());
        }

        @Test
        @DisplayName("验证不存在航班状态更新返回null")
        void testStatusUpdate_FailsForNonExistentFlight() {
            Flight result = flightService.updateFlightStatus("non_existent_flight", "delayed");

            assertNull(result);
        }

        @Test
        @DisplayName("验证状态流转的完整性：scheduled -> delayed -> on_time")
        void testStatusFlow_CompleteLifecycle() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            assertEquals("scheduled", flight.getFlightStatus());

            flightService.updateFlightStatus(flight.getFlightId(), "delayed");
            assertEquals("delayed", flight.getFlightStatus());

            flightService.updateFlightStatus(flight.getFlightId(), "on_time");
            assertEquals("on_time", flight.getFlightStatus());
        }

        @Test
        @DisplayName("验证状态流转的完整性：scheduled -> cancelled")
        void testStatusFlow_CancellationLifecycle() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            assertEquals("scheduled", flight.getFlightStatus());

            flightService.updateFlightStatus(flight.getFlightId(), "cancelled");
            assertEquals("cancelled", flight.getFlightStatus());
        }
    }

    @Nested
    @DisplayName("航班配置管理测试")
    class FlightConfigurationTests {

        @Test
        @DisplayName("验证航班信息创建正确")
        void testCreateFlight_CorrectlyCreatesFlight() {
            Flight flightToCreate = new Flight();
            flightToCreate.setFlightNumber("CA9999");
            flightToCreate.setDeparture("广州");
            flightToCreate.setDestination("深圳");
            flightToCreate.setFlightDeparture(LocalDateTime.now().plusDays(2));
            flightToCreate.setFlightArrival(LocalDateTime.now().plusDays(2).plusHours(1));
            flightToCreate.setFlightSeats(150);
            flightToCreate.setFlightPrice(300.0);

            Flight created = flightService.createFlight(flightToCreate);

            assertNotNull(created.getFlightId());
            assertTrue(created.getFlightId().startsWith("flight_"));
            assertEquals("CA9999", created.getFlightNumber());
            assertEquals("广州-深圳", created.getFlightRoute());
            assertEquals("scheduled", created.getFlightStatus());
            assertEquals(150, created.getFlightAvailable());
            assertEquals(150, created.getFlightSeats());
            assertNotNull(created.getCreatedAt());
        }

        @Test
        @DisplayName("验证航班信息更新正确")
        void testUpdateFlight_CorrectlyUpdatesFlight() {
            Flight original = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(original);

            Flight updates = new Flight();
            updates.setFlightNumber("CA8888");
            updates.setDeparture("杭州");
            updates.setDestination("南京");
            updates.setFlightDeparture(LocalDateTime.now().plusDays(5));
            updates.setFlightArrival(LocalDateTime.now().plusDays(5).plusHours(2));
            updates.setFlightSeats(180);
            updates.setFlightPrice(600.0);

            Flight updated = flightService.updateFlight(original.getFlightId(), updates);

            assertEquals("CA8888", updated.getFlightNumber());
            assertEquals("杭州-南京", updated.getFlightRoute());
            assertEquals(180, updated.getFlightSeats());
            assertEquals(600.0, updated.getFlightPrice(), 0.01);
            assertEquals(original.getFlightId(), updated.getFlightId());
        }

        @Test
        @DisplayName("验证不存在航班更新返回null")
        void testUpdateFlight_FailsForNonExistent() {
            Flight updates = new Flight();
            updates.setFlightNumber("CA7777");

            Flight result = flightService.updateFlight("non_existent_flight", updates);

            assertNull(result);
        }

        @Test
        @DisplayName("验证航班查询正确")
        void testGetFlight_CorrectlyRetrievesFlight() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            Flight retrieved = flightService.getFlight(flight.getFlightId());

            assertNotNull(retrieved);
            assertEquals(flight.getFlightId(), retrieved.getFlightId());
            assertEquals(flight.getFlightNumber(), retrieved.getFlightNumber());
            assertEquals(flight.getFlightRoute(), retrieved.getFlightRoute());
        }

        @Test
        @DisplayName("验证不存在航班查询返回null")
        void testGetFlight_ReturnsNullForNonExistent() {
            Flight result = flightService.getFlight("non_existent_flight");

            assertNull(result);
        }

        @Test
        @DisplayName("验证所有航班查询正确")
        void testGetAllFlights_ReturnsAllFlights() {
            Flight flight1 = TestDataBuilder.createDomesticFlight();
            Flight flight2 = TestDataBuilder.createInternationalFlight();
            Flight flight3 = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight1);
            DataStore.addFlight(flight2);
            DataStore.addFlight(flight3);

            List<Flight> allFlights = flightService.getAllFlights();

            assertEquals(3, allFlights.size());
        }

        @Test
        @DisplayName("验证航班删除正确")
        void testDeleteFlight_CorrectlyDeletesFlight() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            assertEquals(1, DataStore.getFlights().size());

            boolean deleted = flightService.deleteFlight(flight.getFlightId());

            assertTrue(deleted);
            assertEquals(0, DataStore.getFlights().size());
        }

        @Test
        @DisplayName("验证不存在航班删除返回false")
        void testDeleteFlight_ReturnsFalseForNonExistent() {
            boolean result = flightService.deleteFlight("non_existent_flight");

            assertFalse(result);
        }

        @Test
        @DisplayName("验证国内航班配置正确")
        void testDomesticFlight_ConfigurationCorrect() {
            Flight domestic = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(domestic);

            assertNotNull(domestic.getFlightId());
            assertEquals("北京", domestic.getDeparture());
            assertEquals("上海", domestic.getDestination());
            assertEquals("北京-上海", domestic.getFlightRoute());
            assertEquals(200, domestic.getFlightSeats());
            assertEquals(800.0, domestic.getFlightPrice(), 0.01);
        }

        @Test
        @DisplayName("验证国际航班配置正确")
        void testInternationalFlight_ConfigurationCorrect() {
            Flight international = TestDataBuilder.createInternationalFlight();
            DataStore.addFlight(international);

            assertNotNull(international.getFlightId());
            assertEquals("北京", international.getDeparture());
            assertEquals("纽约", international.getDestination());
            assertEquals("北京-纽约", international.getFlightRoute());
            assertEquals(300, international.getFlightSeats());
            assertEquals(5000.0, international.getFlightPrice(), 0.01);
        }
    }

    @Nested
    @DisplayName("航班座位管理测试")
    class FlightSeatManagementTests {

        @Test
        @DisplayName("验证航班创建时可用座位等于总座位")
        void testSeatInitialization_AvailableEqualsTotal() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            assertEquals(flight.getFlightSeats(), flight.getFlightAvailable());
            assertEquals(200, flight.getFlightAvailable());
        }

        @Test
        @DisplayName("验证座位扣减正确")
        void testSeatDeduction_CorrectlyDeductsSeats() {
            Flight flight = TestDataBuilder.createFlightWithAvailableSeats(100);
            DataStore.addFlight(flight);

            int seatsToDeduct = 10;
            int initialAvailable = flight.getFlightAvailable();

            boolean result = flightService.updateAvailableSeats(flight.getFlightId(), -seatsToDeduct);

            assertTrue(result);
            assertEquals(initialAvailable - seatsToDeduct, flight.getFlightAvailable());
        }

        @Test
        @DisplayName("验证座位恢复正确")
        void testSeatRecovery_CorrectlyRecoversSeats() {
            Flight flight = TestDataBuilder.createFlightWithAvailableSeats(90);
            DataStore.addFlight(flight);

            int seatsToRecover = 10;
            int initialAvailable = flight.getFlightAvailable();

            boolean result = flightService.updateAvailableSeats(flight.getFlightId(), seatsToRecover);

            assertTrue(result);
            assertEquals(initialAvailable + seatsToRecover, flight.getFlightAvailable());
        }

        @Test
        @DisplayName("验证座位不能扣减为负数")
        void testSeatValidation_CannotBeNegative() {
            Flight flight = TestDataBuilder.createFlightWithAvailableSeats(5);
            DataStore.addFlight(flight);

            boolean result = flightService.updateAvailableSeats(flight.getFlightId(), -10);

            assertFalse(result);
            assertEquals(5, flight.getFlightAvailable());
        }

        @Test
        @DisplayName("验证座位不能超过总座位数")
        void testSeatValidation_CannotExceedTotal() {
            Flight flight = TestDataBuilder.createFlightWithAvailableSeats(150);
            DataStore.addFlight(flight);

            boolean result = flightService.updateAvailableSeats(flight.getFlightId(), 100);

            assertFalse(result);
            assertEquals(150, flight.getFlightAvailable());
        }

        @Test
        @DisplayName("验证座位扣减为0时航班满座")
        void testSeatDeduction_ZeroMeansFull() {
            Flight flight = TestDataBuilder.createFlightWithAvailableSeats(1);
            DataStore.addFlight(flight);

            boolean result = flightService.updateAvailableSeats(flight.getFlightId(), -1);

            assertTrue(result);
            assertEquals(0, flight.getFlightAvailable());
        }

        @Test
        @DisplayName("验证不存在航班座位更新返回false")
        void testSeatUpdate_FailsForNonExistentFlight() {
            boolean result = flightService.updateAvailableSeats("non_existent_flight", -5);

            assertFalse(result);
        }

        @Test
        @DisplayName("验证座位管理的完整性：扣减-恢复-再扣减")
        void testSeatFlow_CompleteLifecycle() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            int totalSeats = flight.getFlightSeats();

            assertTrue(flightService.updateAvailableSeats(flight.getFlightId(), -50));
            assertEquals(totalSeats - 50, flight.getFlightAvailable());

            assertTrue(flightService.updateAvailableSeats(flight.getFlightId(), 30));
            assertEquals(totalSeats - 20, flight.getFlightAvailable());

            assertTrue(flightService.updateAvailableSeats(flight.getFlightId(), -30));
            assertEquals(totalSeats - 50, flight.getFlightAvailable());

            assertTrue(flightService.updateAvailableSeats(flight.getFlightId(), 50));
            assertEquals(totalSeats, flight.getFlightAvailable());
        }

        @Test
        @DisplayName("验证多航班座位独立管理")
        void testSeatManagement_MultipleFlightsIndependent() {
            Flight flight1 = TestDataBuilder.createDomesticFlight();
            flight1.setFlightId("flight_1");
            DataStore.addFlight(flight1);

            Flight flight2 = TestDataBuilder.createDomesticFlight();
            flight2.setFlightId("flight_2");
            DataStore.addFlight(flight2);

            assertTrue(flightService.updateAvailableSeats("flight_1", -50));
            assertEquals(150, flight1.getFlightAvailable());
            assertEquals(200, flight2.getFlightAvailable());

            assertTrue(flightService.updateAvailableSeats("flight_2", -100));
            assertEquals(150, flight1.getFlightAvailable());
            assertEquals(100, flight2.getFlightAvailable());
        }

        @Test
        @DisplayName("验证国际航班大座位数管理")
        void testSeatManagement_LargeSeatCount() {
            Flight flight = TestDataBuilder.createInternationalFlight();
            DataStore.addFlight(flight);

            assertEquals(300, flight.getFlightSeats());
            assertEquals(300, flight.getFlightAvailable());

            assertTrue(flightService.updateAvailableSeats(flight.getFlightId(), -250));
            assertEquals(50, flight.getFlightAvailable());

            assertTrue(flightService.updateAvailableSeats(flight.getFlightId(), -50));
            assertEquals(0, flight.getFlightAvailable());
        }
    }

    @Nested
    @DisplayName("航班计划管理测试")
    class FlightScheduleTests {

        @Test
        @DisplayName("验证航班计划创建正确")
        void testScheduleCreation_CorrectlyCreatesSchedule() {
            Flight flight = new Flight();
            flight.setFlightNumber("MU5101");
            flight.setDeparture("北京");
            flight.setDestination("上海");

            LocalDateTime departure = LocalDateTime.of(2026, 5, 15, 8, 0);
            LocalDateTime arrival = LocalDateTime.of(2026, 5, 15, 10, 30);
            flight.setFlightDeparture(departure);
            flight.setFlightArrival(arrival);
            flight.setFlightSeats(200);
            flight.setFlightPrice(800.0);

            Flight created = flightService.createFlight(flight);

            assertEquals(departure, created.getFlightDeparture());
            assertEquals(arrival, created.getFlightArrival());
        }

        @Test
        @DisplayName("验证航班计划更新正确")
        void testScheduleUpdate_CorrectlyUpdatesSchedule() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            LocalDateTime newDeparture = LocalDateTime.now().plusDays(10);
            LocalDateTime newArrival = LocalDateTime.now().plusDays(10).plusHours(3);

            Flight updates = new Flight();
            updates.setFlightNumber(flight.getFlightNumber());
            updates.setDeparture(flight.getDeparture());
            updates.setDestination(flight.getDestination());
            updates.setFlightDeparture(newDeparture);
            updates.setFlightArrival(newArrival);
            updates.setFlightSeats(flight.getFlightSeats());
            updates.setFlightPrice(flight.getFlightPrice());

            Flight updated = flightService.updateFlight(flight.getFlightId(), updates);

            assertEquals(newDeparture, updated.getFlightDeparture());
            assertEquals(newArrival, updated.getFlightArrival());
        }

        @Test
        @DisplayName("验证航班到达时间晚于出发时间")
        void testScheduleValidation_ArrivalAfterDeparture() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            assertTrue(flight.getFlightArrival().isAfter(flight.getFlightDeparture()));
        }

        @Test
        @DisplayName("验证多航班计划管理")
        void testScheduleManagement_MultipleFlights() {
            List<Flight> schedule = new ArrayList<>();

            Flight morning = TestDataBuilder.createDomesticFlight();
            morning.setFlightNumber("CA1001");
            morning.setFlightDeparture(LocalDateTime.of(2026, 5, 15, 8, 0));
            morning.setFlightArrival(LocalDateTime.of(2026, 5, 15, 10, 30));
            schedule.add(morning);

            Flight afternoon = TestDataBuilder.createDomesticFlight();
            afternoon.setFlightNumber("CA1002");
            afternoon.setFlightDeparture(LocalDateTime.of(2026, 5, 15, 14, 0));
            afternoon.setFlightArrival(LocalDateTime.of(2026, 5, 15, 16, 30));
            schedule.add(afternoon);

            Flight evening = TestDataBuilder.createDomesticFlight();
            evening.setFlightNumber("CA1003");
            evening.setFlightDeparture(LocalDateTime.of(2026, 5, 15, 20, 0));
            evening.setFlightArrival(LocalDateTime.of(2026, 5, 15, 22, 30));
            schedule.add(evening);

            for (Flight f : schedule) {
                DataStore.addFlight(f);
                assertTrue(f.getFlightArrival().isAfter(f.getFlightDeparture()));
            }

            assertEquals(3, flightService.getAllFlights().size());
        }
    }

    @Nested
    @DisplayName("航班数据一致性测试")
    class FlightDataConsistencyTests {

        @Test
        @DisplayName("验证航班ID唯一性")
        void testFlightId_Unique() {
            Set<String> ids = new HashSet<>();

            for (int i = 0; i < 100; i++) {
                Flight flight = flightService.createFlight(TestDataBuilder.createDomesticFlight());
                assertTrue(ids.add(flight.getFlightId()), "航班ID重复: " + flight.getFlightId());
            }

            assertEquals(100, ids.size());
        }

        @Test
        @DisplayName("验证航班航线正确生成")
        void testFlightRoute_CorrectlyGenerated() {
            Flight flight = new Flight();
            flight.setFlightNumber("CA5555");
            flight.setDeparture("成都");
            flight.setDestination("重庆");
            flight.setFlightDeparture(LocalDateTime.now().plusDays(1));
            flight.setFlightArrival(LocalDateTime.now().plusDays(1).plusHours(1));
            flight.setFlightSeats(100);
            flight.setFlightPrice(500.0);

            Flight created = flightService.createFlight(flight);

            assertEquals("成都-重庆", created.getFlightRoute());
        }

        @Test
        @DisplayName("验证航班创建时间正确设置")
        void testCreatedAt_CorrectlySet() {
            Flight flight = TestDataBuilder.createDomesticFlight();

            LocalDateTime before = LocalDateTime.now().minusSeconds(1);
            Flight created = flightService.createFlight(flight);
            LocalDateTime after = LocalDateTime.now().plusSeconds(1);

            assertNotNull(created.getCreatedAt());
            assertTrue(created.getCreatedAt().isAfter(before));
            assertTrue(created.getCreatedAt().isBefore(after));
        }

        @Test
        @DisplayName("验证航班状态初始值正确")
        void testInitialStatus_CorrectlySet() {
            Flight flight = new Flight();
            flight.setFlightNumber("CA6666");
            flight.setDeparture("西安");
            flight.setDestination("兰州");
            flight.setFlightDeparture(LocalDateTime.now().plusDays(1));
            flight.setFlightArrival(LocalDateTime.now().plusDays(1).plusHours(1));
            flight.setFlightSeats(100);
            flight.setFlightPrice(400.0);

            Flight created = flightService.createFlight(flight);

            assertEquals("scheduled", created.getFlightStatus());
        }

        @Test
        @DisplayName("验证航班更新时ID不变化")
        void testFlightUpdate_IdUnchanged() {
            Flight original = TestDataBuilder.createDomesticFlight();
            Flight created = flightService.createFlight(original);
            String originalId = created.getFlightId();

            Flight updates = new Flight();
            updates.setFlightNumber("CA7777");
            updates.setDeparture("武汉");
            updates.setDestination("长沙");
            updates.setFlightDeparture(LocalDateTime.now().plusDays(5));
            updates.setFlightArrival(LocalDateTime.now().plusDays(5).plusHours(2));
            updates.setFlightSeats(150);
            updates.setFlightPrice(700.0);

            Flight updated = flightService.updateFlight(originalId, updates);

            assertEquals(originalId, updated.getFlightId());
        }
    }
}
