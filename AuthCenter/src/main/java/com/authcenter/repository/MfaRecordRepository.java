package com.authcenter.repository;

import com.authcenter.entity.MfaRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MfaRecordRepository extends JpaRepository<MfaRecord, String> {
    
    Optional<MfaRecord> findByUserIdAndMfaTypeAndVerifiedOrderByCreatedAtDesc(String userId, String mfaType, Boolean verified);
}