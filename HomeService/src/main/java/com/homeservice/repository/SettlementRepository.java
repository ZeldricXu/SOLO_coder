package com.homeservice.repository;

import com.homeservice.entity.Settlement;
import com.homeservice.enums.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SettlementRepository extends JpaRepository<Settlement, Long> {
    Optional<Settlement> findBySettlementId(String settlementId);
    Optional<Settlement> findByBookingId(String bookingId);
    List<Settlement> findByStaffId(String staffId);
    List<Settlement> findBySettlementStatus(SettlementStatus status);
    boolean existsByBookingId(String bookingId);
    @Query("SELECT SUM(s.serviceAmount) FROM Settlement s WHERE s.settlementStatus = :status")
    Double sumServiceAmountByStatus(@Param("status") SettlementStatus status);
    @Query("SELECT SUM(s.staffAmount) FROM Settlement s WHERE s.staffId = :staffId")
    Double sumStaffIncome(@Param("staffId") String staffId);
}
