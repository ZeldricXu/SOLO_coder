package com.social.repository;

import com.social.entity.PrivacySetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PrivacySettingRepository extends JpaRepository<PrivacySetting, Long> {
    Optional<PrivacySetting> findByPrivacyId(String privacyId);
    Optional<PrivacySetting> findByUserId(String userId);
}
