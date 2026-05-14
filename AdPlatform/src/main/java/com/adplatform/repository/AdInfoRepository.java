package com.adplatform.repository;

import com.adplatform.entity.AdInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdInfoRepository extends JpaRepository<AdInfo, String> {
    Optional<AdInfo> findByAdId(String adId);
    List<AdInfo> findByAdStatus(String adStatus);
    List<AdInfo> findByAdvertiser(String advertiser);
    List<AdInfo> findByAdStatusIn(List<String> statuses);
}
