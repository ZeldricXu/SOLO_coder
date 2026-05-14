package com.servicedesk.repository;

import com.servicedesk.entity.Satisfaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SatisfactionRepository extends JpaRepository<Satisfaction, String> {
    Optional<Satisfaction> findByTicketId(String ticketId);
    boolean existsByTicketId(String ticketId);
    
    @Query("SELECT AVG(s.satisfactionScore) FROM Satisfaction s")
    Double findAvgSatisfactionScore();
}
