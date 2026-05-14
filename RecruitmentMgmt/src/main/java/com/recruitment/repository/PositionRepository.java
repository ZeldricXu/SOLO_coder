package com.recruitment.repository;

import com.recruitment.common.enums.PositionStatus;
import com.recruitment.model.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PositionRepository extends JpaRepository<Position, String> {
    Optional<Position> findByPositionId(String positionId);
    List<Position> findByPositionStatus(PositionStatus status);
    List<Position> findByPositionDepartment(String department);
    List<Position> findByPositionType(String type);
    boolean existsByPositionId(String positionId);
}
