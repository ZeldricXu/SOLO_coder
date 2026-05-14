package com.travelbooking.repository;

import com.travelbooking.model.Itinerary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ItineraryRepository extends JpaRepository<Itinerary, String> {
    List<Itinerary> findByBookingId(String bookingId);
    List<Itinerary> findByRouteId(String routeId);
    List<Itinerary> findByGuideId(String guideId);
    Optional<Itinerary> findByBookingIdAndItineraryStatus(String bookingId, String itineraryStatus);
    long countByItineraryStatus(String itineraryStatus);
}
