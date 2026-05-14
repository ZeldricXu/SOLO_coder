package com.recruitment.service;

import com.recruitment.analysis.AnalysisService;
import com.recruitment.common.enums.PositionStatus;
import com.recruitment.common.util.IdGenerator;
import com.recruitment.history.HistoryService;
import com.recruitment.model.Position;
import com.recruitment.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PositionService {
    private final PositionRepository positionRepository;
    private final AnalysisService analysisService;
    private final HistoryService historyService;
    private final PositionTypeConfigService positionTypeConfigService;

    @Transactional
    public Position createPosition(String name, String type, String department,
                                   Integer count, String salary, String requirement) {
        log.info("Position: 创建职位, name: {}, type: {}", name, type);

        validatePositionType(type);

        String positionId = IdGenerator.generatePositionId();
        Position position = Position.builder()
                .positionId(positionId)
                .positionName(name)
                .positionType(type)
                .positionDepartment(department)
                .positionStatus(PositionStatus.DRAFT)
                .positionCount(count)
                .resumeCount(0)
                .positionSalary(salary)
                .positionRequirement(requirement)
                .build();
        Position saved = positionRepository.save(position);
        analysisService.incrementPositionCount();
        historyService.recordPositionCreate(positionId, "创建职位: " + name);
        log.info("Position: 创建职位成功, positionId: {}", positionId);
        return saved;
    }

    @Transactional
    public Position createPosition(String name, String type, String department,
                                   Integer count, String salary, String requirement,
                                   Integer priority) {
        return createPosition(name, type, department, count, salary, requirement);
    }

    @Transactional
    public Position publishPosition(String positionId) {
        Position position = getPosition(positionId);
        if (position.getPositionStatus() == PositionStatus.RECRUITING) {
            throw new RuntimeException("职位已在招聘中");
        }
        String oldStatus = position.getPositionStatus().name();
        position.setPositionStatus(PositionStatus.RECRUITING);
        Position saved = positionRepository.save(position);
        historyService.recordPositionUpdate(positionId, oldStatus, "RECRUITING", "发布职位");
        log.info("Position: 发布职位, positionId: {}", positionId);
        return saved;
    }

    @Transactional
    public Position updatePositionStatus(String positionId, PositionStatus newStatus) {
        Position position = getPosition(positionId);
        String oldStatus = position.getPositionStatus().name();
        position.setPositionStatus(newStatus);
        Position saved = positionRepository.save(position);
        historyService.recordPositionUpdate(positionId, oldStatus, newStatus.name(), "更新职位状态");
        log.info("Position: 更新职位状态, positionId: {}, status: {}", positionId, newStatus);
        return saved;
    }

    @Transactional
    public Position updatePosition(String positionId, String name, String type,
                                    String department, Integer count, String salary,
                                    String requirement) {
        log.info("Position: 更新职位, positionId: {}", positionId);

        Position position = getPosition(positionId);

        if (name != null) position.setPositionName(name);
        if (type != null) {
            validatePositionType(type);
            position.setPositionType(type);
        }
        if (department != null) position.setPositionDepartment(department);
        if (count != null) position.setPositionCount(count);
        if (salary != null) position.setPositionSalary(salary);
        if (requirement != null) position.setPositionRequirement(requirement);

        Position saved = positionRepository.save(position);
        log.info("Position: 职位更新成功, positionId: {}", positionId);
        return saved;
    }

    @Transactional
    public void incrementResumeCount(String positionId) {
        Position position = getPosition(positionId);
        position.setResumeCount(position.getResumeCount() + 1);
        positionRepository.save(position);
        log.debug("Position: 职位简历计数+1, positionId: {}", positionId);
    }

    public Position getPosition(String positionId) {
        return positionRepository.findByPositionId(positionId)
                .orElseThrow(() -> new RuntimeException("职位不存在: " + positionId));
    }

    public List<Position> getAllPositions() {
        return positionRepository.findAll();
    }

    public List<Position> getPositionsByStatus(PositionStatus status) {
        return positionRepository.findByPositionStatus(status);
    }

    public List<Position> getPositionsByDepartment(String department) {
        return positionRepository.findByPositionDepartment(department);
    }

    public List<Position> getPositionsByType(String type) {
        return positionRepository.findByPositionType(type);
    }

    public boolean isPositionRecruiting(String positionId) {
        Position position = getPosition(positionId);
        return position.getPositionStatus() == PositionStatus.RECRUITING;
    }

    public boolean hasAvailableSlots(String positionId) {
        Position position = getPosition(positionId);
        return position.getResumeCount() < position.getPositionCount();
    }

    public void validatePositionType(String type) {
        if (type == null || type.isEmpty()) {
            throw new RuntimeException("职位类型不能为空");
        }
        if (!positionTypeConfigService.isValidPositionType(type)) {
            throw new RuntimeException("无效的职位类型: " + type + "，请先在职位类型配置中添加");
        }
    }

    public void validatePositionForApplication(String positionId) {
        Position position = getPosition(positionId);
        if (position.getPositionStatus() == PositionStatus.CLOSED) {
            throw new RuntimeException("职位已关闭");
        }
        if (position.getPositionStatus() == PositionStatus.SUSPENDED) {
            throw new RuntimeException("职位已暂停招聘");
        }
        if (position.getPositionStatus() != PositionStatus.RECRUITING) {
            throw new RuntimeException("职位未在招聘中");
        }
        if (!hasAvailableSlots(positionId)) {
            throw new RuntimeException("该职位招聘已满，不再接受新的简历投递");
        }
    }

    @Transactional
    public Position closePosition(String positionId) {
        return updatePositionStatus(positionId, PositionStatus.CLOSED);
    }

    @Transactional
    public Position suspendPosition(String positionId) {
        return updatePositionStatus(positionId, PositionStatus.SUSPENDED);
    }

    @Transactional
    public Position resumePosition(String positionId) {
        return updatePositionStatus(positionId, PositionStatus.RECRUITING);
    }

    public String getPositionTypeName(String typeCode) {
        return positionTypeConfigService.getPositionTypeByCode(typeCode)
                .map(config -> config.getTypeName())
                .orElse(typeCode);
    }
}
