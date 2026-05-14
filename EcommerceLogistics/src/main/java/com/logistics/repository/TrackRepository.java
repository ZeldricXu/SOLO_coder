package com.logistics.repository;

import com.logistics.entity.Track;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TrackRepository extends JpaRepository<Track, String> {

    List<Track> findByLogisticsIdOrderByTrackTimeAsc(String logisticsId);

    List<Track> findByLogisticsId(String logisticsId);
}
