package com.maplocation.repository;

import com.maplocation.model.Coordinates;
import com.maplocation.model.Location;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LocationRepository extends MongoRepository<Location, String> {

    @Query("{ '$text': { '$search': ?0 } }")
    List<Location> searchByKeyword(String keyword);

    @Query("{ 'locationCategory': ?0 }")
    List<Location> findByCategory(String category);

    @Query("{ 'locationType': ?0 }")
    List<Location> findByType(String type);

    List<Location> findByLocationIdIn(List<String> locationIds);
}
