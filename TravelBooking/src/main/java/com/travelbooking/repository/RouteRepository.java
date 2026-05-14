package com.travelbooking.repository;

import com.travelbooking.model.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RouteRepository extends JpaRepository<Route, String> {
    List<Route> findByRouteTypeAndRouteStatus(String routeType, String routeStatus);
    List<Route> findByRouteStatus(String routeStatus);
    List<Route> findByRouteType(String routeType);
}
