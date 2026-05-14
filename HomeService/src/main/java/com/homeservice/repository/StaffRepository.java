package com.homeservice.repository;

import com.homeservice.entity.Staff;
import com.homeservice.enums.StaffStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface StaffRepository extends JpaRepository<Staff, Long> {
    Optional<Staff> findByStaffId(String staffId);
    List<Staff> findByStaffStatus(StaffStatus status);
    List<Staff> findByStaffType(String type);
    List<Staff> findByStaffRegion(String region);
    boolean existsByStaffId(String staffId);
    @Query("SELECT COUNT(s) FROM Staff s")
    Long countTotalStaff();
    @Query("SELECT AVG(s.staffRating) FROM Staff s WHERE s.totalReviews > 0")
    Double getAverageRating();
}
