package com.maplocation.repository;

import com.maplocation.model.NearbyQuery;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NearbyQueryRepository extends MongoRepository<NearbyQuery, String> {
}
