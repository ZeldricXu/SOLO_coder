package com.library.librarymgmt.repository;

import com.library.librarymgmt.entity.Reserve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReserveRepository extends JpaRepository<Reserve, String> {
    Optional<Reserve> findByReserveId(String reserveId);
    List<Reserve> findByBookId(String bookId);
    List<Reserve> findByReaderId(String readerId);
    List<Reserve> findByBookIdAndReserveStatusOrderByReserveTimeAsc(String bookId, String status);
    List<Reserve> findByReserveStatus(String status);
    boolean existsByReserveId(String reserveId);
}
