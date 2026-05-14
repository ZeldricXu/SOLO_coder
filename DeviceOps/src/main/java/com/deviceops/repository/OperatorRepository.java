package com.deviceops.repository;

import com.deviceops.entity.Operator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OperatorRepository extends JpaRepository<Operator, String> {

    List<Operator> findByOperatorStatus(String operatorStatus);

    List<Operator> findByOperatorType(String operatorType);

    List<Operator> findByOperatorStatusAndOperatorType(String operatorStatus, String operatorType);

    long countByOperatorStatus(String operatorStatus);
}
