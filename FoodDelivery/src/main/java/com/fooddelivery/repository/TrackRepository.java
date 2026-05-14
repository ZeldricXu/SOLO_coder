package com.fooddelivery.repository;

import com.fooddelivery.entity.Track;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TrackRepository extends JpaRepository<Track, String> {
    List<Track> findByDeliveryIdOrderByTrackTimeAsc(String deliveryId);
    List<Track> findByDeliveryId(String deliveryId);
}
