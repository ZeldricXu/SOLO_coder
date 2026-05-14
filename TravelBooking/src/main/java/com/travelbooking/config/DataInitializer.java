package com.travelbooking.config;

import com.travelbooking.model.*;
import com.travelbooking.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RouteService routeService;
    private final GuideService guideService;
    private final SpotService spotService;
    private final TeamService teamService;

    @Override
    public void run(String... args) {
        initializeRoutes();
        initializeGuides();
        initializeSpots();
        initializeTeams();
    }

    private void initializeRoutes() {
        if (!routeService.getAllRoutes().isEmpty()) {
            return;
        }

        Route route1 = new Route();
        route1.setRouteId("route_001");
        route1.setRouteName("北京五日游");
        route1.setRouteType("domestic");
        route1.setRouteDuration(5);
        route1.setRoutePrice(new BigDecimal("3000.00"));
        route1.setRouteQuota(50);
        route1.setRouteAvailable(30);
        route1.setRouteStatus("available");
        route1.setCreatedAt(Instant.now());
        routeService.createRoute(route1);

        Route route2 = new Route();
        route2.setRouteId("route_002");
        route2.setRouteName("上海杭州三日游");
        route2.setRouteType("domestic");
        route2.setRouteDuration(3);
        route2.setRoutePrice(new BigDecimal("2000.00"));
        route2.setRouteQuota(30);
        route2.setRouteAvailable(25);
        route2.setRouteStatus("available");
        route2.setCreatedAt(Instant.now());
        routeService.createRoute(route2);

        Route route3 = new Route();
        route3.setRouteId("route_003");
        route3.setRouteName("泰国七日游");
        route3.setRouteType("international");
        route3.setRouteDuration(7);
        route3.setRoutePrice(new BigDecimal("8000.00"));
        route3.setRouteQuota(20);
        route3.setRouteAvailable(0);
        route3.setRouteStatus("full");
        route3.setCreatedAt(Instant.now());
        routeService.createRoute(route3);
    }

    private void initializeGuides() {
        if (!guideService.getAllGuides().isEmpty()) {
            return;
        }

        Guide guide1 = new Guide();
        guide1.setGuideId("guide_001");
        guide1.setGuideName("张导游");
        guide1.setGuidePhone("13800138001");
        guide1.setGuideRating(new BigDecimal("4.8"));
        guide1.setGuideStatus("available");
        guide1.setGuideCount(10);
        guide1.setCompletedCount(8);
        guide1.setCreatedAt(Instant.now());
        guideService.createGuide(guide1);

        Guide guide2 = new Guide();
        guide2.setGuideId("guide_002");
        guide2.setGuideName("李导游");
        guide2.setGuidePhone("13800138002");
        guide2.setGuideRating(new BigDecimal("4.5"));
        guide2.setGuideStatus("available");
        guide2.setGuideCount(15);
        guide2.setCompletedCount(12);
        guide2.setCreatedAt(Instant.now());
        guideService.createGuide(guide2);
    }

    private void initializeSpots() {
        if (!spotService.getAllSpots().isEmpty()) {
            return;
        }

        Spot spot1 = new Spot();
        spot1.setSpotId("spot_001");
        spot1.setSpotName("故宫博物院");
        spot1.setSpotLocation("北京市东城区景山前街4号");
        spot1.setSpotType("cultural");
        spot1.setSpotStatus("active");
        spot1.setCreatedAt(Instant.now());
        spotService.createSpot(spot1);

        Spot spot2 = new Spot();
        spot2.setSpotId("spot_002");
        spot2.setSpotName("八达岭长城");
        spot2.setSpotLocation("北京市延庆区");
        spot2.setSpotType("historical");
        spot2.setSpotStatus("active");
        spot2.setCreatedAt(Instant.now());
        spotService.createSpot(spot2);

        Spot spot3 = new Spot();
        spot3.setSpotId("spot_003");
        spot3.setSpotName("西湖");
        spot3.setSpotLocation("浙江省杭州市");
        spot3.setSpotType("natural");
        spot3.setSpotStatus("active");
        spot3.setCreatedAt(Instant.now());
        spotService.createSpot(spot3);
    }

    private void initializeTeams() {
        if (!teamService.getAllTeams().isEmpty()) {
            return;
        }

        Team team1 = new Team();
        team1.setTeamId("team_001");
        team1.setTeamName("金牌团队A");
        team1.setTeamStatus("available");
        team1.setTeamCapacity(30);
        team1.setCreatedAt(Instant.now());
        teamService.createTeam(team1);

        Team team2 = new Team();
        team2.setTeamId("team_002");
        team2.setTeamName("金牌团队B");
        team2.setTeamStatus("available");
        team2.setTeamCapacity(25);
        team2.setCreatedAt(Instant.now());
        teamService.createTeam(team2);
    }
}
