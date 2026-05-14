package com.maplocation.repository;

import com.maplocation.model.DistanceRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DistanceRecordRepository extends MongoRepository<DistanceRecord, String> {
}
