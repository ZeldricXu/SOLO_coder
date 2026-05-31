package com.contractai.ticket.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.contractai.common.context.TenantContext;
import com.contractai.common.exception.BusinessException;
import com.contractai.common.exception.ValidationException;
import com.contractai.skill.entity.Employee;
import com.contractai.skill.entity.EmployeeSkill;
import com.contractai.skill.entity.Skill;
import com.contractai.skill.mapper.EmployeeMapper;
import com.contractai.skill.mapper.EmployeeSkillMapper;
import com.contractai.skill.mapper.SkillMapper;
import com.contractai.ticket.dto.TicketDTO;
import com.contractai.ticket.entity.AssignmentStrategy;
import com.contractai.ticket.entity.EmployeeWorkload;
import com.contractai.ticket.entity.Ticket;
import com.contractai.ticket.entity.TicketAssignmentLog;
import com.contractai.ticket.mapper.AssignmentStrategyMapper;
import com.contractai.ticket.mapper.EmployeeWorkloadMapper;
import com.contractai.ticket.mapper.TicketAssignmentLogMapper;
import com.contractai.ticket.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketMapper ticketMapper;
    private final TicketAssignmentLogMapper assignmentLogMapper;
    private final EmployeeWorkloadMapper workloadMapper;
    private final AssignmentStrategyMapper strategyMapper;
    private final EmployeeMapper employeeMapper;
    private final EmployeeSkillMapper employeeSkillMapper;
    private final SkillMapper skillMapper;

    @Transactional
    public Ticket createTicket(TicketDTO.TicketCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateTicketCreate(dto);

        Ticket ticket = new Ticket();
        ticket.setId(IdUtil.getSnowflakeNextId());
        ticket.setTenantId(tenantId);
        ticket.setTicketNo("TK" + System.currentTimeMillis());
        ticket.setTitle(dto.getTitle());
        ticket.setDescription(dto.getDescription());
        ticket.setTicketType(dto.getTicketType());
        ticket.setPriority(dto.getPriority() != null ? dto.getPriority() : 2);
        ticket.setStatus("pending");
        ticket.setSource(dto.getSource());
        ticket.setCategory(dto.getCategory());
        ticket.setTags(dto.getTags());
        ticket.setRequiredSkills(dto.getRequiredSkills());
        ticket.setSlaPolicyId(dto.getSlaPolicyId());
        ticket.setParentId(dto.getParentId() != null ? dto.getParentId() : 0L);
        ticket.setFormData(dto.getFormData());
        ticket.setCreatedBy(dto.getCreatedBy());

        ticketMapper.insert(ticket);

        if (dto.getAssigneeId() != null) {
            assignTicket(new TicketDTO.TicketAssignDTO() {{
                setTicketId(ticket.getId());
                setAssigneeId(dto.getAssigneeId());
                setAssignmentType("manual");
                setAssignmentReason("创建时手动指定");
                setAssignedBy(dto.getCreatedBy());
            }});
        } else {
            try {
                autoAssignTicket(new TicketDTO.TicketAutoAssignDTO() {{
                    setTicketId(ticket.getId());
                    setStrategyType("hybrid");
                }});
            } catch (Exception e) {
                log.warn("自动分配失败，工单保持待分配状态: {}", e.getMessage());
            }
        }

        return getTicket(ticket.getId());
    }

    @Transactional
    public Ticket updateTicket(Long id, TicketDTO.TicketUpdateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        Ticket ticket = getTicket(id);

        if (dto.getTitle() != null) ticket.setTitle(dto.getTitle());
        if (dto.getDescription() != null) ticket.setDescription(dto.getDescription());
        if (dto.getTicketType() != null) ticket.setTicketType(dto.getTicketType());
        if (dto.getPriority() != null) ticket.setPriority(dto.getPriority());
        if (dto.getStatus() != null) ticket.setStatus(dto.getStatus());
        if (dto.getCategory() != null) ticket.setCategory(dto.getCategory());
        if (dto.getTags() != null) ticket.setTags(dto.getTags());
        if (dto.getRequiredSkills() != null) ticket.setRequiredSkills(dto.getRequiredSkills());
        if (dto.getFormData() != null) ticket.setFormData(dto.getFormData());

        ticketMapper.updateById(ticket);
        return getTicket(id);
    }

    public Page<Ticket> listTickets(int page, int size, String status, String ticketType,
                                    Long assigneeId, String category, Integer priority, String keyword) {
        Long tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId);

        if (status != null) wrapper.eq(Ticket::getStatus, status);
        if (ticketType != null) wrapper.eq(Ticket::getTicketType, ticketType);
        if (assigneeId != null) wrapper.eq(Ticket::getAssigneeId, assigneeId);
        if (category != null) wrapper.eq(Ticket::getCategory, category);
        if (priority != null) wrapper.eq(Ticket::getPriority, priority);
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(Ticket::getTitle, keyword);
        }

        wrapper.orderByDesc(Ticket::getPriority, Ticket::getCreatedAt);
        Page<Ticket> pageResult = ticketMapper.selectPage(new Page<>(page, size), wrapper);

        enrichTicketsWithAssignee(pageResult.getRecords());
        return pageResult;
    }

    public Ticket getTicket(Long id) {
        Long tenantId = TenantContext.getTenantId();
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null || !ticket.getTenantId().equals(tenantId)) {
            throw new BusinessException("工单不存在");
        }

        List<TicketAssignmentLog> logs = assignmentLogMapper.findByTicketId(id, tenantId);
        ticket.setAssignmentLogs(logs);

        if (ticket.getAssigneeId() != null) {
            Employee assignee = employeeMapper.selectById(ticket.getAssigneeId());
            ticket.setAssignee(assignee);
        }

        return ticket;
    }

    @Transactional
    public Ticket assignTicket(TicketDTO.TicketAssignDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        Ticket ticket = getTicket(dto.getTicketId());

        Employee assignee = employeeMapper.selectById(dto.getAssigneeId());
        if (assignee == null || !assignee.getTenantId().equals(tenantId)) {
            throw new BusinessException("处理人不存在");
        }

        Long oldAssigneeId = ticket.getAssigneeId();

        ticket.setAssigneeId(dto.getAssigneeId());
        ticket.setAssigneeGroup(assignee.getDepartment());
        ticket.setStatus("assigned");
        ticketMapper.updateById(ticket);

        TicketAssignmentLog log = new TicketAssignmentLog();
        log.setId(IdUtil.getSnowflakeNextId());
        log.setTenantId(tenantId);
        log.setTicketId(ticket.getId());
        log.setAssignmentType(dto.getAssignmentType() != null ? dto.getAssignmentType() : "manual");
        log.setFromAssigneeId(oldAssigneeId);
        log.setToAssigneeId(dto.getAssigneeId());
        log.setAssignmentReason(dto.getAssignmentReason());
        log.setAssignedBy(dto.getAssignedBy());
        log.setAssignedAt(LocalDateTime.now());
        assignmentLogMapper.insert(log);

        updateEmployeeWorkload(dto.getAssigneeId(), tenantId);
        if (oldAssigneeId != null && !oldAssigneeId.equals(dto.getAssigneeId())) {
            updateEmployeeWorkload(oldAssigneeId, tenantId);
        }

        return getTicket(ticket.getId());
    }

    @Transactional
    public TicketDTO.AssignmentResultDTO autoAssignTicket(TicketDTO.TicketAutoAssignDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        Ticket ticket = getTicket(dto.getTicketId());

        AssignmentStrategy strategy = resolveStrategy(dto, ticket.getTicketType(), tenantId);

        List<Employee> candidates = getCandidateEmployees(dto.getCandidateEmployeeIds(), tenantId);
        if (candidates.isEmpty()) {
            throw new BusinessException("没有可用的候选处理人");
        }

        List<TicketDTO.AssignmentCandidateDTO> scoredCandidates = scoreCandidates(
                candidates, ticket, strategy, tenantId);

        if (scoredCandidates.isEmpty()) {
            throw new BusinessException("没有符合条件的处理人");
        }

        TicketDTO.AssignmentCandidateDTO bestCandidate = scoredCandidates.get(0);

        if (bestCandidate.getFinalScore().compareTo(new BigDecimal("30")) < 0) {
            throw new BusinessException("所有候选处理人评分过低，请手动分配");
        }

        ticket.setAssigneeId(bestCandidate.getEmployeeId());
        ticket.setAssigneeGroup(bestCandidate.getDepartment());
        ticket.setStatus("assigned");
        ticket.setMatchScore(bestCandidate.getMatchScore());
        ticket.setWorkloadScore(bestCandidate.getWorkloadScore());
        ticket.setFinalScore(bestCandidate.getFinalScore());
        ticketMapper.updateById(ticket);

        TicketAssignmentLog log = new TicketAssignmentLog();
        log.setId(IdUtil.getSnowflakeNextId());
        log.setTenantId(tenantId);
        log.setTicketId(ticket.getId());
        log.setAssignmentType("auto");
        log.setToAssigneeId(bestCandidate.getEmployeeId());
        log.setAssignmentReason("自动分配: " + strategy.getStrategyName());
        log.setAssignmentStrategy(strategy.getStrategyType());
        log.setMatchScore(bestCandidate.getMatchScore());
        log.setLoadBalanceFactor(bestCandidate.getWorkloadScore());
        log.setAssignedAt(LocalDateTime.now());
        assignmentLogMapper.insert(log);

        updateEmployeeWorkload(bestCandidate.getEmployeeId(), tenantId);

        TicketDTO.AssignmentResultDTO result = new TicketDTO.AssignmentResultDTO();
        result.setTicketId(ticket.getId());
        result.setTicketNo(ticket.getTicketNo());
        result.setTicketTitle(ticket.getTitle());
        result.setAssignedToId(bestCandidate.getEmployeeId());
        result.setAssignedToName(bestCandidate.getEmployeeName());
        result.setAssignmentStrategy(strategy.getStrategyType());
        result.setMatchScore(bestCandidate.getMatchScore());
        result.setWorkloadScore(bestCandidate.getWorkloadScore());
        result.setFinalScore(bestCandidate.getFinalScore());
        result.setAllCandidates(scoredCandidates);
        result.setAssignmentReason(generateAssignmentReason(bestCandidate, strategy));

        return result;
    }

    private AssignmentStrategy resolveStrategy(TicketDTO.TicketAutoAssignDTO dto,
                                               String ticketType, Long tenantId) {
        if (dto.getStrategyId() != null) {
            AssignmentStrategy strategy = strategyMapper.selectById(dto.getStrategyId());
            if (strategy != null && strategy.getTenantId().equals(tenantId) && Boolean.TRUE.equals(strategy.getEnabled())) {
                return strategy;
            }
        }

        String strategyType = dto.getStrategyType() != null ? dto.getStrategyType() : "hybrid";
        LambdaQueryWrapper<AssignmentStrategy> wrapper = new LambdaQueryWrapper<AssignmentStrategy>()
                .eq(AssignmentStrategy::getTenantId, tenantId)
                .eq(AssignmentStrategy::getStrategyType, strategyType)
                .eq(AssignmentStrategy::getEnabled, true);

        if (ticketType != null) {
            wrapper.and(w -> w.isNull(AssignmentStrategy::getTicketTypes)
                    .or().apply("JSON_CONTAINS(ticket_types, '\"" + ticketType + "\"')"));
        }

        wrapper.orderByDesc(AssignmentStrategy::getCreatedAt).last("LIMIT 1");
        AssignmentStrategy strategy = strategyMapper.selectOne(wrapper);

        if (strategy == null) {
            strategy = createDefaultStrategy(tenantId, strategyType);
        }

        return strategy;
    }

    private AssignmentStrategy createDefaultStrategy(Long tenantId, String strategyType) {
        AssignmentStrategy strategy = new AssignmentStrategy();
        strategy.setId(IdUtil.getSnowflakeNextId());
        strategy.setTenantId(tenantId);
        strategy.setStrategyCode("DEFAULT_" + strategyType.toUpperCase());
        strategy.setStrategyName("默认" + strategyType + "策略");
        strategy.setStrategyType(strategyType);
        strategy.setSkillMatchWeight(new BigDecimal("50.00"));
        strategy.setLoadBalanceWeight(new BigDecimal("30.00"));
        strategy.setEfficiencyWeight(new BigDecimal("20.00"));
        strategy.setEnabled(true);
        strategy.setDescription("系统默认创建的" + strategyType + "分配策略");
        strategyMapper.insert(strategy);
        return strategy;
    }

    private List<Employee> getCandidateEmployees(List<Long> candidateIds, Long tenantId) {
        if (candidateIds != null && !candidateIds.isEmpty()) {
            return employeeMapper.selectBatchIds(candidateIds).stream()
                    .filter(e -> e.getTenantId().equals(tenantId))
                    .collect(Collectors.toList());
        }

        return employeeMapper.selectList(
                new LambdaQueryWrapper<Employee>()
                        .eq(Employee::getTenantId, tenantId)
        );
    }

    private List<TicketDTO.AssignmentCandidateDTO> scoreCandidates(
            List<Employee> candidates, Ticket ticket, AssignmentStrategy strategy, Long tenantId) {

        Map<Long, EmployeeWorkload> workloadMap = getEmployeeWorkloadMap(candidates, tenantId);
        Map<Long, List<EmployeeSkill>> employeeSkillsMap = getEmployeeSkillsMap(candidates, tenantId);
        Map<Long, Skill> skillMap = getSkillMap(ticket.getRequiredSkills());

        List<TicketDTO.AssignmentCandidateDTO> scored = new ArrayList<>();

        for (Employee employee : candidates) {
            TicketDTO.AssignmentCandidateDTO candidate = new TicketDTO.AssignmentCandidateDTO();
            candidate.setEmployeeId(employee.getId());
            candidate.setEmployeeName(employee.getName());
            candidate.setDepartment(employee.getDepartment());
            candidate.setPosition(employee.getPosition());

            BigDecimal matchScore = calculateSkillMatchScore(
                    ticket.getRequiredSkills(),
                    employeeSkillsMap.getOrDefault(employee.getId(), Collections.emptyList()),
                    skillMap,
                    candidate);

            BigDecimal workloadScore = calculateWorkloadScore(
                    workloadMap.get(employee.getId()),
                    ticket.getPriority());

            BigDecimal efficiencyScore = calculateEfficiencyScore(workloadMap.get(employee.getId()));

            BigDecimal skillWeight = strategy.getSkillMatchWeight() != null ? strategy.getSkillMatchWeight() : new BigDecimal("50");
            BigDecimal loadWeight = strategy.getLoadBalanceWeight() != null ? strategy.getLoadBalanceWeight() : new BigDecimal("30");
            BigDecimal effWeight = strategy.getEfficiencyWeight() != null ? strategy.getEfficiencyWeight() : new BigDecimal("20");
            BigDecimal totalWeight = skillWeight.add(loadWeight).add(effWeight);

            BigDecimal finalScore = matchScore.multiply(skillWeight)
                    .add(workloadScore.multiply(loadWeight))
                    .add(efficiencyScore.multiply(effWeight))
                    .divide(totalWeight, 2, RoundingMode.HALF_UP);

            EmployeeWorkload workload = workloadMap.get(employee.getId());
            if (workload != null) {
                candidate.setOpenTicketsCount(workload.getOpenTicketsCount());
                candidate.setCapacity(workload.getCapacity());
            } else {
                candidate.setOpenTicketsCount(0);
                candidate.setCapacity(10);
            }

            candidate.setMatchScore(matchScore);
            candidate.setWorkloadScore(workloadScore);
            candidate.setEfficiencyScore(efficiencyScore);
            candidate.setFinalScore(finalScore);

            scored.add(candidate);
        }

        scored.sort((a, b) -> b.getFinalScore().compareTo(a.getFinalScore()));
        return scored;
    }

    private BigDecimal calculateSkillMatchScore(List<Long> requiredSkillIds,
                                                 List<EmployeeSkill> employeeSkills,
                                                 Map<Long, Skill> skillMap,
                                                 TicketDTO.AssignmentCandidateDTO candidate) {
        if (requiredSkillIds == null || requiredSkillIds.isEmpty()) {
            candidate.setMatchedSkills(Collections.emptyList());
            candidate.setMissingSkills(Collections.emptyList());
            return new BigDecimal("100.00");
        }

        Map<Long, EmployeeSkill> employeeSkillMap = employeeSkills.stream()
                .collect(Collectors.toMap(EmployeeSkill::getSkillId, s -> s, (a, b) -> a));

        List<String> matchedSkills = new ArrayList<>();
        List<String> missingSkills = new ArrayList<>();
        BigDecimal totalScore = BigDecimal.ZERO;
        BigDecimal maxPossibleScore = BigDecimal.ZERO;

        for (Long skillId : requiredSkillIds) {
            Skill skill = skillMap.get(skillId);
            if (skill == null) continue;

            int skillLevel = skill.getLevel() != null ? skill.getLevel() : 1;
            BigDecimal weight = BigDecimal.valueOf(skillLevel);
            maxPossibleScore = maxPossibleScore.add(weight.multiply(BigDecimal.valueOf(100)));

            EmployeeSkill es = employeeSkillMap.get(skillId);
            if (es != null) {
                int proficiency = es.getProficiencyLevel() != null ? es.getProficiencyLevel() : 1;
                BigDecimal skillScore = BigDecimal.valueOf(proficiency * 20);
                totalScore = totalScore.add(weight.multiply(skillScore));
                matchedSkills.add(skill.getSkillName() + "(" + proficiency + "/5)");
            } else {
                missingSkills.add(skill.getSkillName());
            }
        }

        candidate.setMatchedSkills(matchedSkills);
        candidate.setMissingSkills(missingSkills);

        if (maxPossibleScore.compareTo(BigDecimal.ZERO) == 0) {
            return new BigDecimal("100.00");
        }

        return totalScore.divide(maxPossibleScore, 2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .min(new BigDecimal("100.00"));
    }

    private BigDecimal calculateWorkloadScore(EmployeeWorkload workload, Integer ticketPriority) {
        if (workload == null) {
            return new BigDecimal("100.00");
        }

        int openCount = workload.getOpenTicketsCount() != null ? workload.getOpenTicketsCount() : 0;
        int capacity = workload.getCapacity() != null ? workload.getCapacity() : 10;

        if (capacity <= 0) capacity = 10;

        BigDecimal workloadRatio = BigDecimal.valueOf(openCount)
                .divide(BigDecimal.valueOf(capacity), 4, RoundingMode.HALF_UP);

        BigDecimal score = BigDecimal.valueOf(100)
                .subtract(workloadRatio.multiply(BigDecimal.valueOf(80)))
                .max(BigDecimal.ZERO);

        int priority = ticketPriority != null ? ticketPriority : 2;
        if (priority >= 3 && openCount < capacity) {
            score = score.multiply(new BigDecimal("1.1")).min(new BigDecimal("100.00"));
        }

        return score.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateEfficiencyScore(EmployeeWorkload workload) {
        if (workload == null || workload.getEfficiencyFactor() == null) {
            return new BigDecimal("80.00");
        }

        return workload.getEfficiencyFactor().multiply(BigDecimal.valueOf(100))
                .min(new BigDecimal("100.00"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private Map<Long, EmployeeWorkload> getEmployeeWorkloadMap(List<Employee> employees, Long tenantId) {
        List<Long> employeeIds = employees.stream().map(Employee::getId).collect(Collectors.toList());
        if (employeeIds.isEmpty()) return Collections.emptyMap();

        List<EmployeeWorkload> workloads = workloadMapper.selectList(
                new LambdaQueryWrapper<EmployeeWorkload>()
                        .eq(EmployeeWorkload::getTenantId, tenantId)
                        .in(EmployeeWorkload::getEmployeeId, employeeIds)
        );

        return workloads.stream()
                .collect(Collectors.toMap(EmployeeWorkload::getEmployeeId, w -> w, (a, b) -> a));
    }

    private Map<Long, List<EmployeeSkill>> getEmployeeSkillsMap(List<Employee> employees, Long tenantId) {
        List<Long> employeeIds = employees.stream().map(Employee::getId).collect(Collectors.toList());
        if (employeeIds.isEmpty()) return Collections.emptyMap();

        List<EmployeeSkill> skills = employeeSkillMapper.selectList(
                new LambdaQueryWrapper<EmployeeSkill>()
                        .eq(EmployeeSkill::getTenantId, tenantId)
                        .in(EmployeeSkill::getEmployeeId, employeeIds)
        );

        return skills.stream()
                .collect(Collectors.groupingBy(EmployeeSkill::getEmployeeId));
    }

    private Map<Long, Skill> getSkillMap(List<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) return Collections.emptyMap();

        List<Skill> skills = skillMapper.selectBatchIds(skillIds);
        return skills.stream().collect(Collectors.toMap(Skill::getId, s -> s, (a, b) -> a));
    }

    private String generateAssignmentReason(TicketDTO.AssignmentCandidateDTO candidate, AssignmentStrategy strategy) {
        StringBuilder sb = new StringBuilder();
        sb.append("基于").append(strategy.getStrategyName()).append("策略分配。");
        sb.append("综合评分: ").append(candidate.getFinalScore()).append("分。");

        if (candidate.getMatchScore().compareTo(new BigDecimal("80")) >= 0) {
            sb.append("技能匹配度高(").append(candidate.getMatchScore()).append("分)");
        } else if (candidate.getMatchScore().compareTo(new BigDecimal("50")) >= 0) {
            sb.append("技能匹配度中等(").append(candidate.getMatchScore()).append("分)");
        } else {
            sb.append("技能匹配度较低(").append(candidate.getMatchScore()).append("分)");
        }

        if (candidate.getWorkloadScore().compareTo(new BigDecimal("70")) >= 0) {
            sb.append("，负载适中(").append(candidate.getOpenTicketsCount()).append("/")
                    .append(candidate.getCapacity()).append(")");
        } else {
            sb.append("，负载较高(").append(candidate.getOpenTicketsCount()).append("/")
                    .append(candidate.getCapacity()).append(")");
        }

        if (!candidate.getMissingSkills().isEmpty()) {
            sb.append("。待提升技能: ").append(String.join(", ", candidate.getMissingSkills()));
        }

        return sb.toString();
    }

    @Transactional
    public Ticket updateTicketStatus(TicketDTO.TicketStatusUpdateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        Ticket ticket = getTicket(dto.getTicketId());

        String oldStatus = ticket.getStatus();
        String newStatus = dto.getStatus();

        ticket.setStatus(newStatus);

        if ("resolved".equals(newStatus) && !"resolved".equals(oldStatus)) {
            ticket.setResolvedAt(LocalDateTime.now());
        }
        if ("closed".equals(newStatus) && !"closed".equals(oldStatus)) {
            ticket.setClosedAt(LocalDateTime.now());
        }

        ticketMapper.updateById(ticket);

        if (ticket.getAssigneeId() != null) {
            updateEmployeeWorkload(ticket.getAssigneeId(), tenantId);
        }

        return getTicket(ticket.getId());
    }

    @Transactional
    public void updateEmployeeWorkload(Long employeeId, Long tenantId) {
        LambdaQueryWrapper<Ticket> openWrapper = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .eq(Ticket::getAssigneeId, employeeId)
                .in(Ticket::getStatus, "assigned", "in_progress", "pending");
        Integer openCount = Math.toIntExact(ticketMapper.selectCount(openWrapper));

        LambdaQueryWrapper<Ticket> totalWrapper = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .eq(Ticket::getAssigneeId, employeeId);
        Integer totalCount = Math.toIntExact(ticketMapper.selectCount(totalWrapper));

        LambdaQueryWrapper<Ticket> resolvedWrapper = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTenantId, tenantId)
                .eq(Ticket::getAssigneeId, employeeId)
                .in(Ticket::getStatus, "resolved", "closed")
                .isNotNull(Ticket::getResolvedAt)
                .isNotNull(Ticket::getCreatedAt);
        List<Ticket> resolvedTickets = ticketMapper.selectList(resolvedWrapper);

        Integer avgResolutionTime = null;
        BigDecimal efficiencyFactor = new BigDecimal("1.00");
        if (!resolvedTickets.isEmpty()) {
            long totalMinutes = 0;
            int count = 0;
            for (Ticket t : resolvedTickets) {
                if (t.getResolvedAt() != null && t.getCreatedAt() != null) {
                    Duration duration = Duration.between(t.getCreatedAt(), t.getResolvedAt());
                    totalMinutes += duration.toMinutes();
                    count++;
                }
            }
            if (count > 0) {
                avgResolutionTime = (int) (totalMinutes / count);
                if (avgResolutionTime > 0) {
                    efficiencyFactor = BigDecimal.valueOf(480)
                            .divide(BigDecimal.valueOf(avgResolutionTime), 4, RoundingMode.HALF_UP)
                            .min(new BigDecimal("2.00"))
                            .max(new BigDecimal("0.30"));
                }
            }
        }

        BigDecimal workloadScore = calculateWorkloadScoreInternal(openCount, 10);

        EmployeeWorkload workload = workloadMapper.selectOne(
                new LambdaQueryWrapper<EmployeeWorkload>()
                        .eq(EmployeeWorkload::getTenantId, tenantId)
                        .eq(EmployeeWorkload::getEmployeeId, employeeId)
        );

        if (workload == null) {
            workload = new EmployeeWorkload();
            workload.setId(IdUtil.getSnowflakeNextId());
            workload.setTenantId(tenantId);
            workload.setEmployeeId(employeeId);
            workload.setCapacity(10);
            workloadMapper.insert(workload);
        }

        workload.setOpenTicketsCount(openCount);
        workload.setTotalTicketsCount(totalCount);
        workload.setAvgResolutionTime(avgResolutionTime);
        workload.setWorkloadScore(workloadScore);
        workload.setEfficiencyFactor(efficiencyFactor);
        workload.setLastCalculatedAt(LocalDateTime.now());
        workloadMapper.updateById(workload);
    }

    private BigDecimal calculateWorkloadScoreInternal(int openCount, int capacity) {
        if (capacity <= 0) capacity = 10;
        BigDecimal ratio = BigDecimal.valueOf(openCount)
                .divide(BigDecimal.valueOf(capacity), 4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100)
                .subtract(ratio.multiply(BigDecimal.valueOf(80)))
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Scheduled(fixedRate = 300000)
    @Transactional
    public void recalculateAllWorkloads() {
        log.info("Starting workload recalculation task at {}", LocalDateTime.now());
        try {
            Long tenantId = 1L;
            List<Employee> employees = employeeMapper.selectList(
                    new LambdaQueryWrapper<Employee>().eq(Employee::getTenantId, tenantId)
            );
            for (Employee employee : employees) {
                try {
                    TenantContext.setTenantId(employee.getTenantId());
                    updateEmployeeWorkload(employee.getId(), employee.getTenantId());
                } finally {
                    TenantContext.clear();
                }
            }
            log.info("Workload recalculation completed, processed {} employees", employees.size());
        } catch (Exception e) {
            log.error("Workload recalculation task failed", e);
        }
    }

    @Transactional
    public List<TicketDTO.AssignmentResultDTO> batchAutoAssign(TicketDTO.BatchAssignDTO dto) {
        List<TicketDTO.AssignmentResultDTO> results = new ArrayList<>();
        for (Long ticketId : dto.getTicketIds()) {
            try {
                TicketDTO.TicketAutoAssignDTO assignDTO = new TicketDTO.TicketAutoAssignDTO();
                assignDTO.setTicketId(ticketId);
                assignDTO.setStrategyType(dto.getStrategyType());
                assignDTO.setStrategyId(dto.getStrategyId());
                results.add(autoAssignTicket(assignDTO));
            } catch (Exception e) {
                log.warn("批量分配工单 {} 失败: {}", ticketId, e.getMessage());
            }
        }
        return results;
    }

    @Transactional
    public AssignmentStrategy createStrategy(TicketDTO.StrategyCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateStrategyCreate(dto, tenantId);

        AssignmentStrategy strategy = new AssignmentStrategy();
        strategy.setId(IdUtil.getSnowflakeNextId());
        strategy.setTenantId(tenantId);
        strategy.setStrategyCode(dto.getStrategyCode());
        strategy.setStrategyName(dto.getStrategyName());
        strategy.setStrategyType(dto.getStrategyType() != null ? dto.getStrategyType() : "hybrid");
        strategy.setTicketTypes(dto.getTicketTypes());
        strategy.setSkillMatchWeight(dto.getSkillMatchWeight() != null ? dto.getSkillMatchWeight() : new BigDecimal("50.00"));
        strategy.setLoadBalanceWeight(dto.getLoadBalanceWeight() != null ? dto.getLoadBalanceWeight() : new BigDecimal("30.00"));
        strategy.setEfficiencyWeight(dto.getEfficiencyWeight() != null ? dto.getEfficiencyWeight() : new BigDecimal("20.00"));
        strategy.setConfig(dto.getConfig());
        strategy.setEnabled(true);
        strategy.setDescription(dto.getDescription());

        strategyMapper.insert(strategy);
        return strategy;
    }

    @Transactional
    public AssignmentStrategy updateStrategy(Long id, TicketDTO.StrategyUpdateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        AssignmentStrategy strategy = strategyMapper.selectById(id);
        if (strategy == null || !strategy.getTenantId().equals(tenantId)) {
            throw new BusinessException("分配策略不存在");
        }

        if (dto.getStrategyName() != null) strategy.setStrategyName(dto.getStrategyName());
        if (dto.getStrategyType() != null) strategy.setStrategyType(dto.getStrategyType());
        if (dto.getTicketTypes() != null) strategy.setTicketTypes(dto.getTicketTypes());
        if (dto.getSkillMatchWeight() != null) strategy.setSkillMatchWeight(dto.getSkillMatchWeight());
        if (dto.getLoadBalanceWeight() != null) strategy.setLoadBalanceWeight(dto.getLoadBalanceWeight());
        if (dto.getEfficiencyWeight() != null) strategy.setEfficiencyWeight(dto.getEfficiencyWeight());
        if (dto.getConfig() != null) strategy.setConfig(dto.getConfig());
        if (dto.getEnabled() != null) strategy.setEnabled(dto.getEnabled());
        if (dto.getDescription() != null) strategy.setDescription(dto.getDescription());

        strategyMapper.updateById(strategy);
        return strategy;
    }

    public Page<AssignmentStrategy> listStrategies(int page, int size, String strategyType, Boolean enabled) {
        Long tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<AssignmentStrategy> wrapper = new LambdaQueryWrapper<AssignmentStrategy>()
                .eq(AssignmentStrategy::getTenantId, tenantId);

        if (strategyType != null) wrapper.eq(AssignmentStrategy::getStrategyType, strategyType);
        if (enabled != null) wrapper.eq(AssignmentStrategy::getEnabled, enabled);

        wrapper.orderByDesc(AssignmentStrategy::getCreatedAt);
        return strategyMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public AssignmentStrategy getStrategy(Long id) {
        Long tenantId = TenantContext.getTenantId();
        AssignmentStrategy strategy = strategyMapper.selectById(id);
        if (strategy == null || !strategy.getTenantId().equals(tenantId)) {
            throw new BusinessException("分配策略不存在");
        }
        return strategy;
    }

    @Transactional
    public void deleteStrategy(Long id) {
        Long tenantId = TenantContext.getTenantId();
        AssignmentStrategy strategy = strategyMapper.selectById(id);
        if (strategy == null || !strategy.getTenantId().equals(tenantId)) {
            throw new BusinessException("分配策略不存在");
        }
        strategyMapper.deleteById(id);
    }

    public EmployeeWorkload getEmployeeWorkload(Long employeeId) {
        Long tenantId = TenantContext.getTenantId();
        updateEmployeeWorkload(employeeId, tenantId);
        return workloadMapper.selectOne(
                new LambdaQueryWrapper<EmployeeWorkload>()
                        .eq(EmployeeWorkload::getTenantId, tenantId)
                        .eq(EmployeeWorkload::getEmployeeId, employeeId)
        );
    }

    public Page<EmployeeWorkload> listWorkloads(int page, int size) {
        Long tenantId = TenantContext.getTenantId();
        return workloadMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<EmployeeWorkload>()
                        .eq(EmployeeWorkload::getTenantId, tenantId)
                        .orderByDesc(EmployeeWorkload::getWorkloadScore)
        );
    }

    @Transactional
    public List<EmployeeWorkload> recalculateWorkloads(TicketDTO.WorkloadRecalculateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        List<Employee> employees;

        if (Boolean.TRUE.equals(dto.getRecalculateAll())) {
            employees = employeeMapper.selectList(
                    new LambdaQueryWrapper<Employee>().eq(Employee::getTenantId, tenantId)
            );
        } else if (dto.getEmployeeIds() != null && !dto.getEmployeeIds().isEmpty()) {
            employees = employeeMapper.selectBatchIds(dto.getEmployeeIds()).stream()
                    .filter(e -> e.getTenantId().equals(tenantId))
                    .collect(Collectors.toList());
        } else {
            throw new ValidationException("请指定要重新计算的员工或选择全部重新计算");
        }

        List<EmployeeWorkload> results = new ArrayList<>();
        for (Employee employee : employees) {
            updateEmployeeWorkload(employee.getId(), tenantId);
            results.add(getEmployeeWorkload(employee.getId()));
        }
        return results;
    }

    private void enrichTicketsWithAssignee(List<Ticket> tickets) {
        Set<Long> assigneeIds = tickets.stream()
                .map(Ticket::getAssigneeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (assigneeIds.isEmpty()) return;

        Map<Long, Employee> employeeMap = employeeMapper.selectBatchIds(assigneeIds).stream()
                .collect(Collectors.toMap(Employee::getId, e -> e, (a, b) -> a));

        for (Ticket ticket : tickets) {
            if (ticket.getAssigneeId() != null) {
                ticket.setAssignee(employeeMap.get(ticket.getAssigneeId()));
            }
        }
    }

    private void validateTicketCreate(TicketDTO.TicketCreateDTO dto) {
        if (dto.getTitle() == null || dto.getTitle().trim().isEmpty()) {
            throw new ValidationException("工单标题不能为空");
        }
        if (dto.getTicketType() == null || dto.getTicketType().trim().isEmpty()) {
            throw new ValidationException("工单类型不能为空");
        }
    }

    private void validateStrategyCreate(TicketDTO.StrategyCreateDTO dto, Long tenantId) {
        if (dto.getStrategyCode() == null || dto.getStrategyCode().trim().isEmpty()) {
            throw new ValidationException("策略编码不能为空");
        }

        LambdaQueryWrapper<AssignmentStrategy> wrapper = new LambdaQueryWrapper<AssignmentStrategy>()
                .eq(AssignmentStrategy::getTenantId, tenantId)
                .eq(AssignmentStrategy::getStrategyCode, dto.getStrategyCode());
        if (strategyMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("策略编码已存在");
        }
    }
}
