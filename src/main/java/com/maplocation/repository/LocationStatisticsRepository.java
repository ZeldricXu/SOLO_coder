package com.maplocation.repository;

import com.maplocation.model.LocationStatistics;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface LocationStatisticsRepository extends MongoRepository<LocationStatistics, String> {
    Optional<LocationStatistics> findByStatDate(LocalDate statDate);
}
