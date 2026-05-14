package com.travelbooking.service;

import com.travelbooking.builder.TestDataBuilder;
import com.travelbooking.exception.BusinessException;
import com.travelbooking.model.*;
import com.travelbooking.repository.ItineraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ItineraryServiceTest {

    @Mock
    private ItineraryRepository itineraryRepository;

    @Mock
    private GuideService guideService;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private HistoryService historyService;

    @Mock
    private RouteService routeService;

    @InjectMocks
    private ItineraryService itineraryService;

    @InjectMocks
    private ItineraryReminderService reminderService;

    private Booking booking;
    private Route domesticRoute;
    private Route internationalRoute;
    private Team team;
    private Guide availableGuide;
    private Itinerary pendingItinerary;

    @BeforeEach
    void setUp() {
        booking = TestDataBuilder.buildConfirmedBooking();
        domesticRoute = TestDataBuilder.buildDomesticRoute();
        internationalRoute = TestDataBuilder.buildInternationalRoute();
        team = TestDataBuilder.buildAvailableTeam();
        availableGuide = TestDataBuilder.buildAvailableGuide();
        pendingItinerary = TestDataBuilder.buildPendingItinerary();
    }

    @Test
    @DisplayName("测试创建行程时状态设置为待出发")
    void testCreateItinerarySetsPendingStatus() {
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Itinerary result = itineraryService.createItinerary(booking, domesticRoute, team);

        assertNotNull(result);
        assertEquals("pending_departure", result.getItineraryStatus());
        assertEquals(booking.getBookingId(), result.getBookingId());
        assertEquals(domesticRoute.getRouteId(), result.getRouteId());
        assertEquals(team.getTeamId(), result.getTeamId());

        verify(itineraryRepository).save(any(Itinerary.class));
    }

    @Test
    @DisplayName("测试行程出发前提醒触发 - 长行程提前3天提醒")
    void testLongTripReminderTriggeredThreeDaysBefore() {
        ItineraryReminderService localReminderService = new ItineraryReminderService(
                itineraryService,
                routeService
        );

        Itinerary longTripItinerary = TestDataBuilder.buildItineraryDueForReminder(
                3,
                7,
                "route_test_002"
        );

        when(routeService.getRouteById("route_test_002")).thenReturn(Optional.of(internationalRoute));

        List<Itinerary> itineraries = Collections.singletonList(longTripItinerary);

        List<ItineraryReminderService.ReminderRecord> reminders =
                localReminderService.checkAndGenerateReminders(itineraries);

        assertFalse(reminders.isEmpty());
        assertEquals(1, reminders.size());
        assertTrue(reminders.get(0).getMessage().contains("3天后出发"));
        assertEquals(1, reminders.get(0).getReminderType());
    }

    @Test
    @DisplayName("测试行程出发前提醒触发 - 短行程提前1天提醒")
    void testShortTripReminderTriggeredOneDayBefore() {
        ItineraryReminderService localReminderService = new ItineraryReminderService(
                itineraryService,
                routeService
        );

        Itinerary shortTripItinerary = TestDataBuilder.buildItineraryDueForReminder(
                1,
                5,
                "route_test_001"
        );

        when(routeService.getRouteById("route_test_001")).thenReturn(Optional.of(domesticRoute));

        List<Itinerary> itineraries = Collections.singletonList(shortTripItinerary);

        List<ItineraryReminderService.ReminderRecord> reminders =
                localReminderService.checkAndGenerateReminders(itineraries);

        assertFalse(reminders.isEmpty());
        assertEquals(1, reminders.size());
        assertTrue(reminders.get(0).getMessage().contains("1天后出发"));
    }

    @Test
    @DisplayName("测试当天出发提醒")
    void testSameDayDepartureReminder() {
        ItineraryReminderService localReminderService = new ItineraryReminderService(
                itineraryService,
                routeService
        );

        Itinerary todayItinerary = TestDataBuilder.buildItineraryDepartingToday();

        List<Itinerary> itineraries = Collections.singletonList(todayItinerary);

        List<ItineraryReminderService.ReminderRecord> reminders =
                localReminderService.checkAndGenerateReminders(itineraries);

        assertFalse(reminders.isEmpty());
        assertEquals(1, reminders.size());
        assertTrue(reminders.get(0).getMessage().contains("今天出发"));
        assertEquals(2, reminders.get(0).getReminderType());
    }

    @Test
    @DisplayName("测试长行程和短行程提醒频率差异")
    void testReminderFrequencyDifferenceBetweenLongAndShortTrips() {
        ItineraryReminderService localReminderService = new ItineraryReminderService(
                itineraryService,
                routeService
        );

        assertEquals(3, ItineraryReminderService.ItineraryType.LONG_TRIP.getReminderDaysBefore());
        assertEquals(1, ItineraryReminderService.ItineraryType.SHORT_TRIP.getReminderDaysBefore());

        assertTrue(ItineraryReminderService.ItineraryType.LONG_TRIP.getReminderDaysBefore() >
                ItineraryReminderService.ItineraryType.SHORT_TRIP.getReminderDaysBefore());
    }

    @Test
    @DisplayName("测试行程类型判定 - 7天及以上为长行程")
    void testItineraryTypeDeterminationLongTrip() {
        ItineraryReminderService localReminderService = new ItineraryReminderService(
                itineraryService,
                routeService
        );

        Route longRoute = new Route();
        longRoute.setRouteDuration(7);

        ItineraryReminderService.ItineraryType type = localReminderService.determineItineraryType(longRoute);

        assertEquals(ItineraryReminderService.ItineraryType.LONG_TRIP, type);
    }

    @Test
    @DisplayName("测试行程类型判定 - 7天以下为短行程")
    void testItineraryTypeDeterminationShortTrip() {
        ItineraryReminderService localReminderService = new ItineraryReminderService(
                itineraryService,
                routeService
        );

        Route shortRoute = new Route();
        shortRoute.setRouteDuration(5);

        ItineraryReminderService.ItineraryType type = localReminderService.determineItineraryType(shortRoute);

        assertEquals(ItineraryReminderService.ItineraryType.SHORT_TRIP, type);
    }

    @Test
    @DisplayName("测试行程状态流转 - 待出发 -> 已出发")
    void testItineraryStatusTransitionToDeparted() {
        when(itineraryRepository.findById("itinerary_test_001")).thenReturn(Optional.of(pendingItinerary));
        when(guideService.assignGuide()).thenReturn(availableGuide);
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Itinerary result = itineraryService.departItinerary("itinerary_test_001");

        assertNotNull(result);
        assertEquals("departed", result.getItineraryStatus());
        assertEquals(availableGuide.getGuideId(), result.getGuideId());
        assertEquals(LocalDate.now(), result.getItineraryStart());

        verify(guideService).incrementGuideCount(availableGuide.getGuideId());
        verify(analyticsService).updateDepartedStatistics();
    }

    @Test
    @DisplayName("测试行程状态流转 - 已出发 -> 已完成")
    void testItineraryStatusTransitionToCompleted() {
        Itinerary departedItinerary = TestDataBuilder.buildDepartedItinerary();

        when(itineraryRepository.findById("itinerary_test_003")).thenReturn(Optional.of(departedItinerary));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Itinerary result = itineraryService.completeItinerary("itinerary_test_003");

        assertNotNull(result);
        assertEquals("completed", result.getItineraryStatus());
        assertEquals(LocalDate.now(), result.getItineraryEnd());

        verify(guideService).incrementCompletedCount("guide_test_001");
        verify(analyticsService).updateCompletedStatistics();
    }

    @Test
    @DisplayName("测试完整生命周期 - 待出发 -> 已出发 -> 已完成")
    void testFullItineraryLifecycle() {
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Itinerary created = itineraryService.createItinerary(booking, domesticRoute, team);
        assertEquals("pending_departure", created.getItineraryStatus());

        when(itineraryRepository.findById(created.getItineraryId())).thenReturn(Optional.of(created));
        when(guideService.assignGuide()).thenReturn(availableGuide);

        Itinerary departed = itineraryService.departItinerary(created.getItineraryId());
        assertEquals("departed", departed.getItineraryStatus());

        when(itineraryRepository.findById(departed.getItineraryId())).thenReturn(Optional.of(departed));

        Itinerary completed = itineraryService.completeItinerary(departed.getItineraryId());
        assertEquals("completed", completed.getItineraryStatus());
    }

    @Test
    @DisplayName("测试已完成行程不能再次出发")
    void testCompletedItineraryCannotDepart() {
        Itinerary completedItinerary = TestDataBuilder.buildCompletedItinerary();

        when(itineraryRepository.findById("itinerary_test_004")).thenReturn(Optional.of(completedItinerary));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            itineraryService.departItinerary("itinerary_test_004");
        });

        assertEquals(400, exception.getCode());
        assertEquals("行程已完成", exception.getMessage());
        verify(guideService, never()).assignGuide();
    }

    @Test
    @DisplayName("测试已完成行程不能重复完成")
    void testCompletedItineraryCannotCompleteAgain() {
        Itinerary completedItinerary = TestDataBuilder.buildCompletedItinerary();

        when(itineraryRepository.findById("itinerary_test_004")).thenReturn(Optional.of(completedItinerary));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            itineraryService.completeItinerary("itinerary_test_004");
        });

        assertEquals(400, exception.getCode());
        assertEquals("行程已完成", exception.getMessage());
    }

    @Test
    @DisplayName("测试导游分配规则 - 选择评分最高的导游")
    void testGuideAssignmentSelectsHighestRated() {
        when(itineraryRepository.findById("itinerary_test_001")).thenReturn(Optional.of(pendingItinerary));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Guide highestRated = TestDataBuilder.buildTopRatedGuide();
        when(guideService.assignGuide()).thenReturn(highestRated);

        Itinerary result = itineraryService.departItinerary("itinerary_test_001");

        assertEquals(highestRated.getGuideId(), result.getGuideId());
        verify(guideService).incrementGuideCount(highestRated.getGuideId());
    }

    @Test
    @DisplayName("测试导游不足时拒绝出发")
    void testDepartureRejectedWhenNoGuidesAvailable() {
        when(itineraryRepository.findById("itinerary_test_001")).thenReturn(Optional.of(pendingItinerary));
        when(guideService.assignGuide()).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            itineraryService.departItinerary("itinerary_test_001");
        });

        assertEquals(400, exception.getCode());
        assertEquals("导游不足", exception.getMessage());
        verify(itineraryRepository, never()).save(any(Itinerary.class));
    }

    @Test
    @DisplayName("测试非待出发状态不触发提醒")
    void testNonPendingItineraryDoesNotTriggerReminder() {
        ItineraryReminderService localReminderService = new ItineraryReminderService(
                itineraryService,
                routeService
        );

        Itinerary departed = TestDataBuilder.buildDepartedItinerary();
        departed.setItineraryStart(LocalDate.now().plusDays(3));

        List<Itinerary> itineraries = Collections.singletonList(departed);

        List<ItineraryReminderService.ReminderRecord> reminders =
                localReminderService.checkAndGenerateReminders(itineraries);

        assertTrue(reminders.isEmpty());
    }

    @Test
    @DisplayName("测试未到提醒时间不触发提醒")
    void testReminderNotTriggeredBeforeDueTime() {
        ItineraryReminderService localReminderService = new ItineraryReminderService(
                itineraryService,
                routeService
        );

        Itinerary futureItinerary = TestDataBuilder.buildItineraryDueForReminder(
                10,
                5,
                "route_test_001"
        );

        when(routeService.getRouteById("route_test_001")).thenReturn(Optional.of(domesticRoute));

        List<Itinerary> itineraries = Collections.singletonList(futureItinerary);

        List<ItineraryReminderService.ReminderRecord> reminders =
                localReminderService.checkAndGenerateReminders(itineraries);

        assertTrue(reminders.isEmpty());
    }

    @Test
    @DisplayName("测试行程出发后记录历史")
    void testHistoryRecordedAfterDeparture() {
        when(itineraryRepository.findById("itinerary_test_001")).thenReturn(Optional.of(pendingItinerary));
        when(guideService.assignGuide()).thenReturn(availableGuide);
        when(itineraryRepository.save(any(Itinerary.class))).thenReturn(pendingItinerary);

        itineraryService.departItinerary("itinerary_test_001");

        verify(historyService).recordHistory(
                eq("itinerary"),
                eq("itinerary_test_001"),
                eq("depart"),
                anyString()
        );
    }

    @Test
    @DisplayName("测试行程完成后记录历史")
    void testHistoryRecordedAfterCompletion() {
        Itinerary departed = TestDataBuilder.buildDepartedItinerary();

        when(itineraryRepository.findById("itinerary_test_003")).thenReturn(Optional.of(departed));
        when(itineraryRepository.save(any(Itinerary.class))).thenReturn(departed);

        itineraryService.completeItinerary("itinerary_test_003");

        verify(historyService).recordHistory(
                eq("itinerary"),
                eq("itinerary_test_003"),
                eq("complete"),
                anyString()
        );
    }

    @Test
    @DisplayName("测试行程不存在时抛出异常")
    void testExceptionWhenItineraryNotFound() {
        when(itineraryRepository.findById("nonexistent")).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            itineraryService.departItinerary("nonexistent");
        });

        assertEquals(404, exception.getCode());
        assertEquals("行程不存在", exception.getMessage());
    }

    @Test
    @DisplayName("测试多行程批量提醒检查")
    void testBulkReminderCheckForMultipleItineraries() {
        ItineraryReminderService localReminderService = new ItineraryReminderService(
                itineraryService,
                routeService
        );

        Itinerary shortDue = TestDataBuilder.buildItineraryDueForReminder(1, 5, "route_short");
        Itinerary longDue = TestDataBuilder.buildItineraryDueForReminder(3, 7, "route_long");
        Itinerary notDue = TestDataBuilder.buildItineraryDueForReminder(10, 5, "route_not_due");
        Itinerary alreadyDeparted = TestDataBuilder.buildDepartedItinerary();

        Route shortRoute = new Route();
        shortRoute.setRouteDuration(5);
        Route longRoute = new Route();
        longRoute.setRouteDuration(7);

        when(routeService.getRouteById("route_short")).thenReturn(Optional.of(shortRoute));
        when(routeService.getRouteById("route_long")).thenReturn(Optional.of(longRoute));
        when(routeService.getRouteById("route_not_due")).thenReturn(Optional.of(shortRoute));

        List<Itinerary> itineraries = Arrays.asList(shortDue, longDue, notDue, alreadyDeparted);

        List<ItineraryReminderService.ReminderRecord> reminders =
                localReminderService.checkAndGenerateReminders(itineraries);

        assertEquals(2, reminders.size());
    }
}
