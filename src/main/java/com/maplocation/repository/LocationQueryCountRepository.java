package com.maplocation.repository;

import com.maplocation.model.LocationQueryCount;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LocationQueryCountRepository extends MongoRepository<LocationQueryCount, String> {
    List<LocationQueryCount> findTop10ByOrderByQueryCountDesc();
}
