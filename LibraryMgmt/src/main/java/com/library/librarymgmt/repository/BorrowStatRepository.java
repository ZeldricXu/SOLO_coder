package com.library.librarymgmt.repository;

import com.library.librarymgmt.entity.BorrowStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BorrowStatRepository extends JpaRepository<BorrowStat, String> {
    Optional<BorrowStat> findByStatMonth(String statMonth);
    Optional<BorrowStat> findByStatId(String statId);
    boolean existsByStatMonth(String statMonth);
}
