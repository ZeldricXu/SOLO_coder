package com.travelbooking.repository;

import com.travelbooking.model.Guide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GuideRepository extends JpaRepository<Guide, String> {
    List<Guide> findByGuideStatus(String guideStatus);
}
