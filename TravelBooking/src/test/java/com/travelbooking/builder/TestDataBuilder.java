package com.travelbooking.builder;

import com.travelbooking.dto.CreateBookingRequest;
import com.travelbooking.model.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public class TestDataBuilder {

    public static Route buildDomesticRoute() {
        Route route = new Route();
        route.setRouteId("route_test_001");
        route.setRouteName("北京五日游");
        route.setRouteType("domestic");
        route.setRouteDuration(5);
        route.setRoutePrice(new BigDecimal("3000.00"));
        route.setRouteQuota(50);
        route.setRouteAvailable(30);
        route.setRouteStatus("available");
        route.setCreatedAt(Instant.now());
        return route;
    }

    public static Route buildInternationalRoute() {
        Route route = new Route();
        route.setRouteId("route_test_002");
        route.setRouteName("泰国七日游");
        route.setRouteType("international");
        route.setRouteDuration(7);
        route.setRoutePrice(new BigDecimal("8000.00"));
        route.setRouteQuota(20);
        route.setRouteAvailable(20);
        route.setRouteStatus("available");
        route.setCreatedAt(Instant.now());
        return route;
    }

    public static Route buildFullRoute() {
        Route route = new Route();
        route.setRouteId("route_test_003");
        route.setRouteName("已满线路");
        route.setRouteType("domestic");
        route.setRouteDuration(3);
        route.setRoutePrice(new BigDecimal("2000.00"));
        route.setRouteQuota(10);
        route.setRouteAvailable(0);
        route.setRouteStatus("full");
        route.setCreatedAt(Instant.now());
        return route;
    }

    public static Route buildClosedRoute() {
        Route route = new Route();
        route.setRouteId("route_test_004");
        route.setRouteName("已关闭线路");
        route.setRouteType("domestic");
        route.setRouteDuration(4);
        route.setRoutePrice(new BigDecimal("2500.00"));
        route.setRouteQuota(20);
        route.setRouteAvailable(15);
        route.setRouteStatus("closed");
        route.setCreatedAt(Instant.now());
        return route;
    }

    public static Route buildRouteWithQuota(int quota, int available) {
        Route route = new Route();
        route.setRouteId("route_quota_" + quota + "_" + available);
        route.setRouteName("测试线路");
        route.setRouteType("domestic");
        route.setRouteDuration(5);
        route.setRoutePrice(new BigDecimal("3000.00"));
        route.setRouteQuota(quota);
        route.setRouteAvailable(available);
        route.setRouteStatus(available > 0 ? "available" : "full");
        route.setCreatedAt(Instant.now());
        return route;
    }

    public static Route buildAvailableRoute() {
        return buildRouteWithQuota(50, 30);
    }

    public static Tourist buildTourist() {
        Tourist tourist = new Tourist();
        tourist.setTouristId("tourist_test_001");
        tourist.setTouristName("张三");
        tourist.setTouristPhone("13800138001");
        tourist.setTouristIdType("identity");
        tourist.setTouristIdNumber("110101199001011234");
        tourist.setRegisteredAt(Instant.now());
        return tourist;
    }

    public static Tourist buildTourist(String name, String phone) {
        Tourist tourist = new Tourist();
        tourist.setTouristId("tourist_" + System.currentTimeMillis());
        tourist.setTouristName(name);
        tourist.setTouristPhone(phone);
        tourist.setTouristIdType("identity");
        tourist.setTouristIdNumber("110101199001011234");
        tourist.setRegisteredAt(Instant.now());
        return tourist;
    }

    public static Booking buildConfirmedBooking() {
        Booking booking = new Booking();
        booking.setBookingId("booking_test_001");
        booking.setRouteId("route_test_001");
        booking.setTouristId("tourist_test_001");
        booking.setBookingCount(2);
        booking.setBookingAmount(new BigDecimal("6000.00"));
        booking.setBookingStatus("confirmed");
        booking.setBookingTime(Instant.now());
        booking.setConfirmedAt(Instant.now());
        return booking;
    }

    public static Booking buildCompletedBooking() {
        Booking booking = new Booking();
        booking.setBookingId("booking_test_002");
        booking.setRouteId("route_test_001");
        booking.setTouristId("tourist_test_001");
        booking.setBookingCount(1);
        booking.setBookingAmount(new BigDecimal("3000.00"));
        booking.setBookingStatus("completed");
        booking.setBookingTime(Instant.now().minusSeconds(86400));
        booking.setConfirmedAt(Instant.now().minusSeconds(86400));
        return booking;
    }

    public static Booking buildBooking(String bookingId, String routeId, String touristId, int count, String status) {
        Booking booking = new Booking();
        booking.setBookingId(bookingId);
        booking.setRouteId(routeId);
        booking.setTouristId(touristId);
        booking.setBookingCount(count);
        booking.setBookingAmount(new BigDecimal("3000.00").multiply(new BigDecimal(count)));
        booking.setBookingStatus(status);
        booking.setBookingTime(Instant.now());
        booking.setConfirmedAt(Instant.now());
        return booking;
    }

    public static Itinerary buildPendingItinerary() {
        Itinerary itinerary = new Itinerary();
        itinerary.setItineraryId("itinerary_test_001");
        itinerary.setBookingId("booking_test_001");
        itinerary.setRouteId("route_test_001");
        itinerary.setTeamId("team_test_001");
        itinerary.setItineraryStatus("pending_departure");
        itinerary.setItineraryStart(LocalDate.now().plusDays(7));
        itinerary.setItineraryEnd(LocalDate.now().plusDays(12));
        return itinerary;
    }

    public static Itinerary buildLongTripItinerary() {
        Itinerary itinerary = new Itinerary();
        itinerary.setItineraryId("itinerary_test_002");
        itinerary.setBookingId("booking_test_002");
        itinerary.setRouteId("route_test_002");
        itinerary.setTeamId("team_test_001");
        itinerary.setItineraryStatus("pending_departure");
        itinerary.setItineraryStart(LocalDate.now().plusDays(10));
        itinerary.setItineraryEnd(LocalDate.now().plusDays(17));
        return itinerary;
    }

    public static Itinerary buildDepartedItinerary() {
        Itinerary itinerary = new Itinerary();
        itinerary.setItineraryId("itinerary_test_003");
        itinerary.setBookingId("booking_test_003");
        itinerary.setRouteId("route_test_001");
        itinerary.setGuideId("guide_test_001");
        itinerary.setTeamId("team_test_001");
        itinerary.setItineraryStatus("departed");
        itinerary.setItineraryStart(LocalDate.now().minusDays(2));
        itinerary.setItineraryEnd(LocalDate.now().plusDays(3));
        return itinerary;
    }

    public static Itinerary buildCompletedItinerary() {
        Itinerary itinerary = new Itinerary();
        itinerary.setItineraryId("itinerary_test_004");
        itinerary.setBookingId("booking_test_004");
        itinerary.setRouteId("route_test_001");
        itinerary.setGuideId("guide_test_001");
        itinerary.setTeamId("team_test_001");
        itinerary.setItineraryStatus("completed");
        itinerary.setItineraryStart(LocalDate.now().minusDays(10));
        itinerary.setItineraryEnd(LocalDate.now().minusDays(5));
        return itinerary;
    }

    public static Itinerary buildItineraryDueForReminder(int daysBeforeDeparture, int routeDuration, String routeId) {
        LocalDate startDate = LocalDate.now().plusDays(daysBeforeDeparture);
        Itinerary itinerary = new Itinerary();
        itinerary.setItineraryId("itinerary_reminder_" + daysBeforeDeparture + "_" + System.currentTimeMillis());
        itinerary.setBookingId("booking_reminder_" + daysBeforeDeparture);
        itinerary.setRouteId(routeId);
        itinerary.setTeamId("team_test_001");
        itinerary.setItineraryStatus("pending_departure");
        itinerary.setItineraryStart(startDate);
        itinerary.setItineraryEnd(startDate.plusDays(routeDuration));
        return itinerary;
    }

    public static Itinerary buildItineraryDepartingToday() {
        LocalDate today = LocalDate.now();
        Itinerary itinerary = new Itinerary();
        itinerary.setItineraryId("itinerary_depart_today_" + System.currentTimeMillis());
        itinerary.setBookingId("booking_depart_today");
        itinerary.setRouteId("route_test_001");
        itinerary.setTeamId("team_test_001");
        itinerary.setItineraryStatus("pending_departure");
        itinerary.setItineraryStart(today);
        itinerary.setItineraryEnd(today.plusDays(5));
        return itinerary;
    }

    public static Itinerary buildItineraryWithStatus(String itineraryId, String status) {
        Itinerary itinerary = new Itinerary();
        itinerary.setItineraryId(itineraryId);
        itinerary.setBookingId("booking_" + itineraryId);
        itinerary.setRouteId("route_test_001");
        itinerary.setTeamId("team_test_001");
        itinerary.setItineraryStatus(status);
        itinerary.setItineraryStart(LocalDate.now().plusDays(7));
        itinerary.setItineraryEnd(LocalDate.now().plusDays(12));
        return itinerary;
    }

    public static Guide buildAvailableGuide() {
        Guide guide = new Guide();
        guide.setGuideId("guide_test_001");
        guide.setGuideName("张导游");
        guide.setGuidePhone("13800138002");
        guide.setGuideRating(new BigDecimal("4.8"));
        guide.setGuideStatus("available");
        guide.setGuideCount(10);
        guide.setCompletedCount(8);
        guide.setCreatedAt(Instant.now());
        return guide;
    }

    public static Guide buildTopRatedGuide() {
        Guide guide = new Guide();
        guide.setGuideId("guide_test_002");
        guide.setGuideName("李导游");
        guide.setGuidePhone("13800138003");
        guide.setGuideRating(new BigDecimal("4.95"));
        guide.setGuideStatus("available");
        guide.setGuideCount(20);
        guide.setCompletedCount(18);
        guide.setCreatedAt(Instant.now());
        return guide;
    }

    public static Guide buildGuide(String guideId, String status, BigDecimal rating) {
        Guide guide = new Guide();
        guide.setGuideId(guideId);
        guide.setGuideName("导游" + guideId);
        guide.setGuidePhone("13800" + guideId);
        guide.setGuideRating(rating);
        guide.setGuideStatus(status);
        guide.setGuideCount(0);
        guide.setCompletedCount(0);
        guide.setCreatedAt(Instant.now());
        return guide;
    }

    public static Team buildAvailableTeam() {
        Team team = new Team();
        team.setTeamId("team_test_001");
        team.setTeamName("金牌团队A");
        team.setTeamStatus("available");
        team.setTeamCapacity(30);
        team.setCreatedAt(Instant.now());
        return team;
    }

    public static Team buildTeam(String teamId, String status, int capacity) {
        Team team = new Team();
        team.setTeamId(teamId);
        team.setTeamName("团队" + teamId);
        team.setTeamStatus(status);
        team.setTeamCapacity(capacity);
        team.setCreatedAt(Instant.now());
        return team;
    }

    public static Spot buildActiveSpot() {
        Spot spot = new Spot();
        spot.setSpotId("spot_test_001");
        spot.setSpotName("故宫博物院");
        spot.setSpotLocation("北京市东城区");
        spot.setSpotType("cultural");
        spot.setSpotStatus("active");
        spot.setCreatedAt(Instant.now());
        return spot;
    }

    public static Settlement buildPaidSettlement() {
        Settlement settlement = new Settlement();
        settlement.setSettlementId("settlement_test_001");
        settlement.setBookingId("booking_test_001");
        settlement.setTouristId("tourist_test_001");
        settlement.setSettlementAmount(new BigDecimal("6000.00"));
        settlement.setPaymentMethod("wechat");
        settlement.setPaymentStatus("paid");
        settlement.setSettlementTime(Instant.now());
        return settlement;
    }

    public static TravelStat buildMonthlyStat() {
        TravelStat stat = new TravelStat();
        stat.setStatId("stat_test_001");
        stat.setStatMonth(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
        stat.setRouteCount(10);
        stat.setBookingCount(50);
        stat.setTouristCount(100);
        stat.setTotalAmount(new BigDecimal("300000.00"));
        stat.setDepartedCount(45);
        stat.setCompletedCount(40);
        return stat;
    }

    public static CreateBookingRequest buildValidBookingRequest() {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setRouteId("route_test_001");
        request.setTouristName("测试游客");
        request.setTouristPhone("13800138999");
        request.setTouristIdType("identity");
        request.setTouristIdNumber("110101199001019999");
        request.setBookingCount(2);
        return request;
    }

    public static CreateBookingRequest buildEmergencyBookingRequest() {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setRouteId("route_test_001");
        request.setTouristName("紧急游客");
        request.setTouristPhone("13800138888");
        request.setBookingCount(1);
        return request;
    }

    public static CreateBookingRequest buildBookingRequest(String routeId, String touristName, int count) {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setRouteId(routeId);
        request.setTouristName(touristName);
        request.setTouristPhone("13800" + System.currentTimeMillis());
        request.setBookingCount(count);
        return request;
    }
}
