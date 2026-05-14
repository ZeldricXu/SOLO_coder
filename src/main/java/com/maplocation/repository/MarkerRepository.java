package com.maplocation.repository;

import com.maplocation.model.Marker;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarkerRepository extends MongoRepository<Marker, String> {
    Optional<Marker> findByLocationId(String locationId);
    List<Marker> findByLocationIdIn(List<String> locationIds);
}
