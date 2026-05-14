package com.deviceops.service.operator;

import com.deviceops.entity.Operator;
import com.deviceops.exception.DeviceOpsException;
import com.deviceops.repository.OperatorRepository;
import com.deviceops.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class OperatorService {

    @Autowired
    private OperatorRepository operatorRepository;

    @Transactional
    public Operator createOperator(String name, String type) {
        Operator operator = new Operator();
        operator.setOperatorId(IdGenerator.generateOperatorId());
        operator.setOperatorName(name);
        operator.setOperatorType(type);
        operator.setOperatorStatus("available");
        operator.setOperatorCount(0);
        return operatorRepository.save(operator);
    }

    public Operator getOperator(String operatorId) {
        return operatorRepository.findById(operatorId)
                .orElseThrow(() -> DeviceOpsException.operatorNotFound(operatorId));
    }

    public List<Operator> getAllOperators() {
        return operatorRepository.findAll();
    }

    public List<Operator> getAvailableOperators() {
        return operatorRepository.findByOperatorStatus("available");
    }

    public List<Operator> getOperatorsByType(String type) {
        return operatorRepository.findByOperatorType(type);
    }

    public List<Operator> getAvailableOperatorsByType(String type) {
        return operatorRepository.findByOperatorStatusAndOperatorType("available", type);
    }

    @Transactional
    public Operator assignOperator(String faultType) {
        List<Operator> operators = getAvailableOperatorsByType(faultType);
        if (operators.isEmpty()) {
            operators = getAvailableOperators();
        }
        if (operators.isEmpty()) {
            throw DeviceOpsException.noAvailableOperator();
        }

        Operator selected = operators.get(0);
        selected.setOperatorStatus("busy");
        return operatorRepository.save(selected);
    }

    @Transactional
    public Operator releaseOperator(String operatorId) {
        Operator operator = getOperator(operatorId);
        operator.setOperatorStatus("available");
        return operatorRepository.save(operator);
    }

    @Transactional
    public Operator incrementCompletedCount(String operatorId) {
        Operator operator = getOperator(operatorId);
        operator.setOperatorCount(operator.getOperatorCount() + 1);
        return operatorRepository.save(operator);
    }

    @Transactional
    public Operator updateOperator(String operatorId, String name, String type, String status) {
        Operator operator = getOperator(operatorId);
        if (name != null) {
            operator.setOperatorName(name);
        }
        if (type != null) {
            operator.setOperatorType(type);
        }
        if (status != null) {
            operator.setOperatorStatus(status);
        }
        return operatorRepository.save(operator);
    }

    @Transactional
    public void deleteOperator(String operatorId) {
        if (!operatorRepository.existsById(operatorId)) {
            throw DeviceOpsException.operatorNotFound(operatorId);
        }
        operatorRepository.deleteById(operatorId);
    }

    public long countAvailable() {
        return operatorRepository.countByOperatorStatus("available");
    }

    public long count() {
        return operatorRepository.count();
    }

    public Optional<Operator> findOptimalOperator(String faultType) {
        List<Operator> available = getAvailableOperatorsByType(faultType);
        if (!available.isEmpty()) {
            return Optional.of(available.get(0));
        }

        List<Operator> allAvailable = getAvailableOperators();
        if (!allAvailable.isEmpty()) {
            return Optional.of(allAvailable.get(0));
        }

        return Optional.empty();
    }
}
