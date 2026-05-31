package com.contractai.approval.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.contractai.approval.dto.ApprovalDTO;
import com.contractai.approval.entity.ApprovalProcess;
import com.contractai.approval.entity.ApprovalRule;
import com.contractai.approval.entity.ApprovalStage;
import com.contractai.approval.entity.ApprovalTask;
import com.contractai.approval.mapper.ApprovalProcessMapper;
import com.contractai.approval.mapper.ApprovalRuleMapper;
import com.contractai.approval.mapper.ApprovalStageMapper;
import com.contractai.approval.mapper.ApprovalTaskMapper;
import com.contractai.common.context.TenantContext;
import com.contractai.common.exception.BusinessException;
import com.contractai.common.exception.ValidationException;
import com.contractai.skill.entity.Employee;
import com.contractai.skill.mapper.EmployeeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ApprovalRuleMapper ruleMapper;
    private final ApprovalProcessMapper processMapper;
    private final ApprovalStageMapper stageMapper;
    private final ApprovalTaskMapper taskMapper;
    private final EmployeeMapper employeeMapper;

    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    @Transactional
    public ApprovalRule createRule(ApprovalDTO.RuleCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateRuleCreate(dto, tenantId);

        ApprovalRule rule = new ApprovalRule();
        rule.setId(IdUtil.getSnowflakeNextId());
        rule.setTenantId(tenantId);
        rule.setRuleCode(dto.getRuleCode());
        rule.setRuleName(dto.getRuleName());
        rule.setRuleType(dto.getRuleType() != null ? dto.getRuleType() : "approver");
        rule.setBusinessType(dto.getBusinessType());
        rule.setPriority(dto.getPriority() != null ? dto.getPriority() : 0);
        rule.setConditionExpression(dto.getConditionExpression());
        rule.setApprovalStrategy(dto.getApprovalStrategy() != null ? dto.getApprovalStrategy() : "any");
        rule.setApproverCount(dto.getApproverCount());
        rule.setApprovalPercentage(dto.getApprovalPercentage());
        rule.setApproverConfig(dto.getApproverConfig());
        rule.setCcConfig(dto.getCcConfig());
        rule.setTimeoutConfig(dto.getTimeoutConfig());
        rule.setEnabled(true);
        rule.setDescription(dto.getDescription());

        ruleMapper.insert(rule);
        return rule;
    }

    @Transactional
    public ApprovalRule updateRule(Long id, ApprovalDTO.RuleUpdateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        ApprovalRule rule = ruleMapper.selectById(id);
        if (rule == null || !rule.getTenantId().equals(tenantId)) {
            throw new BusinessException("审批规则不存在");
        }

        if (dto.getRuleName() != null) rule.setRuleName(dto.getRuleName());
        if (dto.getRuleType() != null) rule.setRuleType(dto.getRuleType());
        if (dto.getBusinessType() != null) rule.setBusinessType(dto.getBusinessType());
        if (dto.getPriority() != null) rule.setPriority(dto.getPriority());
        if (dto.getConditionExpression() != null) rule.setConditionExpression(dto.getConditionExpression());
        if (dto.getApprovalStrategy() != null) rule.setApprovalStrategy(dto.getApprovalStrategy());
        if (dto.getApproverCount() != null) rule.setApproverCount(dto.getApproverCount());
        if (dto.getApprovalPercentage() != null) rule.setApprovalPercentage(dto.getApprovalPercentage());
        if (dto.getApproverConfig() != null) rule.setApproverConfig(dto.getApproverConfig());
        if (dto.getCcConfig() != null) rule.setCcConfig(dto.getCcConfig());
        if (dto.getTimeoutConfig() != null) rule.setTimeoutConfig(dto.getTimeoutConfig());
        if (dto.getEnabled() != null) rule.setEnabled(dto.getEnabled());
        if (dto.getDescription() != null) rule.setDescription(dto.getDescription());

        ruleMapper.updateById(rule);
        return rule;
    }

    public Page<ApprovalRule> listRules(int page, int size, String ruleType, String businessType, Boolean enabled) {
        Long tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<ApprovalRule> wrapper = new LambdaQueryWrapper<ApprovalRule>()
                .eq(ApprovalRule::getTenantId, tenantId);

        if (ruleType != null) wrapper.eq(ApprovalRule::getRuleType, ruleType);
        if (businessType != null) wrapper.eq(ApprovalRule::getBusinessType, businessType);
        if (enabled != null) wrapper.eq(ApprovalRule::getEnabled, enabled);

        wrapper.orderByDesc(ApprovalRule::getPriority, ApprovalRule::getCreatedAt);
        return ruleMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public ApprovalRule getRule(Long id) {
        Long tenantId = TenantContext.getTenantId();
        ApprovalRule rule = ruleMapper.selectById(id);
        if (rule == null || !rule.getTenantId().equals(tenantId)) {
            throw new BusinessException("审批规则不存在");
        }
        return rule;
    }

    @Transactional
    public void deleteRule(Long id) {
        Long tenantId = TenantContext.getTenantId();
        ApprovalRule rule = ruleMapper.selectById(id);
        if (rule == null || !rule.getTenantId().equals(tenantId)) {
            throw new BusinessException("审批规则不存在");
        }
        ruleMapper.deleteById(id);
    }

    @Transactional
    public ApprovalDTO.ProcessStartResultDTO startProcess(ApprovalDTO.ProcessStartDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateProcessStart(dto);

        List<ApprovalRule> matchedRules = findMatchingRules(dto.getBusinessType(), dto.getFormData(),
                dto.getVariables(), dto.getStartedBy(), tenantId);

        List<ApprovalDTO.StageConfigDTO> stageConfigs = buildStageConfigs(matchedRules, dto);

        ApprovalProcess process = new ApprovalProcess();
        process.setId(IdUtil.getSnowflakeNextId());
        process.setTenantId(tenantId);
        process.setProcessNo("AP" + System.currentTimeMillis());
        process.setBusinessType(dto.getBusinessType());
        process.setBusinessId(dto.getBusinessId());
        process.setTitle(dto.getTitle());
        process.setStatus("approving");
        process.setApprovalStrategy(dto.getApprovalStrategy() != null ? dto.getApprovalStrategy() : "sequential");
        process.setCurrentStage(0);
        process.setTotalStages(stageConfigs.size());
        process.setFormData(dto.getFormData());
        process.setVariables(dto.getVariables());
        process.setApproverList(dto.getApproverList());
        process.setCcList(dto.getCcList());
        process.setStartedBy(dto.getStartedBy());
        process.setStartedAt(LocalDateTime.now());
        if (dto.getTimeoutMinutes() != null) {
            process.setTimeoutAt(LocalDateTime.now().plusMinutes(dto.getTimeoutMinutes()));
        }

        processMapper.insert(process);

        List<ApprovalStage> stages = createStages(process.getId(), stageConfigs, tenantId);
        process.setStages(stages);

        if (!stages.isEmpty()) {
            ApprovalStage firstStage = stages.get(0);
            firstStage.setStatus("approving");
            firstStage.setStartedAt(LocalDateTime.now());
            stageMapper.updateById(firstStage);

            List<ApprovalTask> tasks = createTasksForStage(process.getId(), firstStage, tenantId);
            process.setTasks(tasks);
        }

        Employee starter = employeeMapper.selectById(dto.getStartedBy());
        process.setStarter(starter);

        ApprovalDTO.ProcessStartResultDTO result = new ApprovalDTO.ProcessStartResultDTO();
        result.setProcessId(process.getId());
        result.setProcessNo(process.getProcessNo());
        result.setStatus(process.getStatus());
        result.setTotalStages(process.getTotalStages());
        result.setCurrentStage(process.getCurrentStage());
        result.setStartedAt(process.getStartedAt());
        result.setCurrentTasks(convertToTaskDTOs(process.getTasks()));

        return result;
    }

    private List<ApprovalRule> findMatchingRules(String businessType, Map<String, Object> formData,
                                                 Map<String, Object> variables, Long starterId, Long tenantId) {
        List<ApprovalRule> allRules = ruleMapper.selectList(
                new LambdaQueryWrapper<ApprovalRule>()
                        .eq(ApprovalRule::getTenantId, tenantId)
                        .eq(ApprovalRule::getBusinessType, businessType)
                        .eq(ApprovalRule::getEnabled, true)
                        .orderByDesc(ApprovalRule::getPriority, ApprovalRule::getCreatedAt)
        );

        List<ApprovalRule> matchedRules = new ArrayList<>();
        for (ApprovalRule rule : allRules) {
            if (rule.getConditionExpression() == null || rule.getConditionExpression().trim().isEmpty()) {
                matchedRules.add(rule);
                continue;
            }

            try {
                boolean matches = evaluateCondition(rule.getConditionExpression(), formData, variables, starterId);
                if (matches) {
                    matchedRules.add(rule);
                }
            } catch (Exception e) {
                log.warn("评估规则条件失败，跳过规则: {}", rule.getRuleCode(), e);
            }
        }

        return matchedRules;
    }

    private List<ApprovalDTO.StageConfigDTO> buildStageConfigs(List<ApprovalRule> rules, ApprovalDTO.ProcessStartDTO dto) {
        List<ApprovalDTO.StageConfigDTO> stages = new ArrayList<>();

        if (dto.getApproverList() != null && !dto.getApproverList().isEmpty()) {
            int stageIndex = 0;
            for (Long approverId : dto.getApproverList()) {
                ApprovalDTO.StageConfigDTO stage = new ApprovalDTO.StageConfigDTO();
                stage.setStageName("第" + (++stageIndex) + "级审批");
                stage.setApprovalStrategy("any");
                stage.setSignType("or_sign");
                stage.setApproverIds(Collections.singletonList(approverId));
                stage.setApproverCount(1);
                stages.add(stage);
            }
            return stages;
        }

        int stageIndex = 0;
        for (ApprovalRule rule : rules) {
            if (!"approver".equals(rule.getRuleType())) continue;

            List<Long> approverIds = resolveApprovers(rule, dto.getFormData(), dto.getVariables(), dto.getStartedBy());
            if (approverIds.isEmpty()) continue;

            ApprovalDTO.StageConfigDTO stage = new ApprovalDTO.StageConfigDTO();
            stage.setStageName(rule.getRuleName());
            stage.setApprovalStrategy(rule.getApprovalStrategy());
            stage.setSignType(determineSignType(rule.getApprovalStrategy()));
            stage.setApproverIds(approverIds);
            stage.setApproverCount(rule.getApproverCount() != null ? rule.getApproverCount() : approverIds.size());
            stage.setApprovalPercentage(rule.getApprovalPercentage());
            stage.setConditionExpression(rule.getConditionExpression());
            stages.add(stage);
        }

        if (stages.isEmpty()) {
            throw new BusinessException("未找到匹配的审批规则或审批人，请检查配置");
        }

        return stages;
    }

    private String determineSignType(String approvalStrategy) {
        if ("all".equals(approvalStrategy)) {
            return "countersign";
        }
        return "or_sign";
    }

    private List<Long> resolveApprovers(ApprovalRule rule, Map<String, Object> formData,
                                        Map<String, Object> variables, Long starterId) {
        Map<String, Object> config = rule.getApproverConfig();
        if (config == null) return Collections.emptyList();

        List<Long> approverIds = new ArrayList<>();

        if (config.containsKey("fixedApprovers")) {
            List<Number> fixedIds = (List<Number>) config.get("fixedApprovers");
            for (Number id : fixedIds) {
                approverIds.add(id.longValue());
            }
        }

        if (config.containsKey("strategy")) {
            String strategy = (String) config.get("strategy");
            List<Long> dynamicApprovers = resolveDynamicApprovers(strategy, formData, variables, starterId);
            approverIds.addAll(dynamicApprovers);
        }

        if (config.containsKey("byRole")) {
            List<String> roles = (List<String>) config.get("byRole");
            List<Long> roleApprovers = findEmployeesByRoles(roles);
            approverIds.addAll(roleApprovers);
        }

        if (config.containsKey("byDepartment")) {
            String department = (String) config.get("byDepartment");
            List<Long> deptApprovers = findEmployeesByDepartment(department);
            approverIds.addAll(deptApprovers);
        }

        if (config.containsKey("byManagerLevel")) {
            Integer level = (Integer) config.get("byManagerLevel");
            List<Long> managerApprovers = findManagersByLevel(starterId, level);
            approverIds.addAll(managerApprovers);
        }

        return approverIds.stream().distinct().collect(Collectors.toList());
    }

    private List<Long> resolveDynamicApprovers(String strategy, Map<String, Object> formData,
                                               Map<String, Object> variables, Long starterId) {
        List<Long> approvers = new ArrayList<>();

        switch (strategy) {
            case "starter_manager":
                Employee starter = employeeMapper.selectById(starterId);
                if (starter != null && starter.getAttributes() != null) {
                    Object managerId = starter.getAttributes().get("managerId");
                    if (managerId != null) {
                        approvers.add(((Number) managerId).longValue());
                    }
                }
                break;
            case "department_head":
                Employee emp = employeeMapper.selectById(starterId);
                if (emp != null && emp.getDepartment() != null) {
                    List<Employee> heads = employeeMapper.selectList(
                            new LambdaQueryWrapper<Employee>()
                                    .eq(Employee::getTenantId, TenantContext.getTenantId())
                                    .eq(Employee::getDepartment, emp.getDepartment())
                                    .like(Employee::getPosition, "主管")
                                    .or()
                                    .like(Employee::getPosition, "经理")
                                    .or()
                                    .like(Employee::getPosition, "总监")
                    );
                    for (Employee head : heads) {
                        approvers.add(head.getId());
                    }
                }
                break;
            case "hr_specialist":
                List<Employee> hrs = employeeMapper.selectList(
                        new LambdaQueryWrapper<Employee>()
                                .eq(Employee::getTenantId, TenantContext.getTenantId())
                                .like(Employee::getDepartment, "人力")
                                .or()
                                .like(Employee::getDepartment, "HR")
                );
                for (Employee hr : hrs) {
                    approvers.add(hr.getId());
                }
                break;
            case "finance_specialist":
                List<Employee> finances = employeeMapper.selectList(
                        new LambdaQueryWrapper<Employee>()
                                .eq(Employee::getTenantId, TenantContext.getTenantId())
                                .like(Employee::getDepartment, "财务")
                                .or()
                                .like(Employee::getDepartment, "Finance")
                );
                for (Employee finance : finances) {
                    approvers.add(finance.getId());
                }
                break;
            default:
                break;
        }

        return approvers;
    }

    private List<Long> findEmployeesByRoles(List<String> roles) {
        List<Long> ids = new ArrayList<>();
        for (String role : roles) {
            List<Employee> employees = employeeMapper.selectList(
                    new LambdaQueryWrapper<Employee>()
                            .eq(Employee::getTenantId, TenantContext.getTenantId())
                            .apply("JSON_CONTAINS(attributes, '\"" + role + "\"', '$.roles')")
            );
            for (Employee emp : employees) {
                ids.add(emp.getId());
            }
        }
        return ids;
    }

    private List<Long> findEmployeesByDepartment(String department) {
        List<Employee> employees = employeeMapper.selectList(
                new LambdaQueryWrapper<Employee>()
                        .eq(Employee::getTenantId, TenantContext.getTenantId())
                        .eq(Employee::getDepartment, department)
        );
        return employees.stream().map(Employee::getId).collect(Collectors.toList());
    }

    private List<Long> findManagersByLevel(Long employeeId, int level) {
        List<Long> managers = new ArrayList<>();
        Long currentId = employeeId;
        for (int i = 0; i < level; i++) {
            Employee emp = employeeMapper.selectById(currentId);
            if (emp == null || emp.getAttributes() == null) break;
            Object managerId = emp.getAttributes().get("managerId");
            if (managerId == null) break;
            currentId = ((Number) managerId).longValue();
            managers.add(currentId);
        }
        return managers;
    }

    private List<ApprovalStage> createStages(Long processId, List<ApprovalDTO.StageConfigDTO> stageConfigs, Long tenantId) {
        List<ApprovalStage> stages = new ArrayList<>();
        for (int i = 0; i < stageConfigs.size(); i++) {
            ApprovalDTO.StageConfigDTO config = stageConfigs.get(i);
            ApprovalStage stage = new ApprovalStage();
            stage.setId(IdUtil.getSnowflakeNextId());
            stage.setTenantId(tenantId);
            stage.setProcessId(processId);
            stage.setStageIndex(i);
            stage.setStageName(config.getStageName() != null ? config.getStageName() : "第" + (i + 1) + "阶段");
            stage.setApprovalStrategy(config.getApprovalStrategy() != null ? config.getApprovalStrategy() : "any");
            stage.setStatus("pending");
            stage.setApproverCount(config.getApproverIds().size());
            stage.setApprovedCount(0);
            stage.setRejectedCount(0);
            stage.setSignType(config.getSignType());
            stageMapper.insert(stage);
            stages.add(stage);
        }
        return stages;
    }

    private List<ApprovalTask> createTasksForStage(Long processId, ApprovalStage stage, Long tenantId) {
        List<ApprovalDTO.StageConfigDTO> configs = buildStageConfigsForProcess(processId);
        if (stage.getStageIndex() >= configs.size()) return Collections.emptyList();

        ApprovalDTO.StageConfigDTO config = configs.get(stage.getStageIndex());
        List<ApprovalTask> tasks = new ArrayList<>();

        for (Long approverId : config.getApproverIds()) {
            ApprovalTask task = new ApprovalTask();
            task.setId(IdUtil.getSnowflakeNextId());
            task.setTenantId(tenantId);
            task.setProcessId(processId);
            task.setStageId(stage.getId());
            task.setApproverId(approverId);
            task.setStatus("pending");
            task.setAssignedAt(LocalDateTime.now());
            taskMapper.insert(task);
            tasks.add(task);
        }

        return tasks;
    }

    private List<ApprovalDTO.StageConfigDTO> buildStageConfigsForProcess(Long processId) {
        Long tenantId = TenantContext.getTenantIdSafe();
        List<ApprovalStage> stages = stageMapper.findByProcessId(processId, tenantId);
        List<ApprovalDTO.StageConfigDTO> configs = new ArrayList<>();

        for (ApprovalStage stage : stages) {
            ApprovalDTO.StageConfigDTO config = new ApprovalDTO.StageConfigDTO();
            config.setStageName(stage.getStageName());
            config.setApprovalStrategy(stage.getApprovalStrategy());
            config.setSignType(stage.getSignType());
            config.setApproverCount(stage.getApproverCount());

            List<ApprovalTask> tasks = taskMapper.findByStageId(stage.getId(), tenantId);
            List<Long> approverIds = tasks.stream()
                    .map(ApprovalTask::getApproverId)
                    .distinct()
                    .collect(Collectors.toList());
            config.setApproverIds(approverIds);

            configs.add(config);
        }

        return configs;
    }

    @Transactional
    public ApprovalTask approve(ApprovalDTO.ApproveDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        ApprovalTask task = taskMapper.selectById(dto.getTaskId());
        if (task == null || !task.getTenantId().equals(tenantId)) {
            throw new BusinessException("审批任务不存在");
        }
        if (!"pending".equals(task.getStatus())) {
            throw new BusinessException("当前任务状态不支持审批操作");
        }
        if (!task.getApproverId().equals(dto.getApproverId())) {
            throw new BusinessException("无权处理此审批任务");
        }

        task.setAction(dto.getAction());
        task.setComment(dto.getComment());
        task.setSignatures(dto.getSignatures());
        task.setStatus("completed");
        task.setActedAt(LocalDateTime.now());

        if ("transfer".equals(dto.getAction()) && dto.getTransferTo() != null) {
            task.setTransferredTo(dto.getTransferTo());
            task.setStatus("transferred");
            taskMapper.updateById(task);

            ApprovalTask newTask = new ApprovalTask();
            newTask.setId(IdUtil.getSnowflakeNextId());
            newTask.setTenantId(tenantId);
            newTask.setProcessId(task.getProcessId());
            newTask.setStageId(task.getStageId());
            newTask.setApproverId(dto.getTransferTo());
            newTask.setStatus("pending");
            newTask.setAssignedAt(LocalDateTime.now());
            taskMapper.insert(newTask);
            return newTask;
        }

        if ("delegate".equals(dto.getAction()) && dto.getDelegateTo() != null) {
            task.setDelegatedTo(dto.getDelegateTo());
            task.setStatus("delegated");
            taskMapper.updateById(task);

            ApprovalTask newTask = new ApprovalTask();
            newTask.setId(IdUtil.getSnowflakeNextId());
            newTask.setTenantId(tenantId);
            newTask.setProcessId(task.getProcessId());
            newTask.setStageId(task.getStageId());
            newTask.setApproverId(dto.getDelegateTo());
            newTask.setStatus("pending");
            newTask.setAssignedAt(LocalDateTime.now());
            taskMapper.insert(newTask);
            return newTask;
        }

        taskMapper.updateById(task);

        processStageCompletion(task.getStageId(), task.getProcessId(), dto.getAction());

        return task;
    }

    @Transactional
    public void processStageCompletion(Long stageId, Long processId, String action) {
        Long tenantId = TenantContext.getTenantId();
        ApprovalStage stage = stageMapper.selectById(stageId);
        ApprovalProcess process = processMapper.selectById(processId);

        if (stage == null || process == null) return;

        if ("approve".equals(action)) {
            stage.setApprovedCount(stage.getApprovedCount() + 1);
        } else if ("reject".equals(action)) {
            stage.setRejectedCount(stage.getRejectedCount() + 1);
        }

        boolean stageCompleted = evaluateStageCompletion(stage, process);

        if (stageCompleted) {
            if (stage.getRejectedCount() > 0 && "all".equals(stage.getApprovalStrategy())) {
                stage.setStatus("rejected");
                stage.setCompletedAt(LocalDateTime.now());
                stageMapper.updateById(stage);

                process.setStatus("rejected");
                process.setFinalDecision("rejected");
                process.setCompletedAt(LocalDateTime.now());
                processMapper.updateById(process);
                return;
            }

            stage.setStatus("approved");
            stage.setCompletedAt(LocalDateTime.now());
            stageMapper.updateById(stage);

            int nextStageIndex = stage.getStageIndex() + 1;
            if (nextStageIndex < process.getTotalStages()) {
                process.setCurrentStage(nextStageIndex);
                processMapper.updateById(process);

                ApprovalStage nextStage = stageMapper.selectOne(
                        new LambdaQueryWrapper<ApprovalStage>()
                                .eq(ApprovalStage::getProcessId, processId)
                                .eq(ApprovalStage::getStageIndex, nextStageIndex)
                );
                if (nextStage != null) {
                    nextStage.setStatus("approving");
                    nextStage.setStartedAt(LocalDateTime.now());
                    stageMapper.updateById(nextStage);
                    createTasksForStage(processId, nextStage, tenantId);
                }
            } else {
                process.setStatus("approved");
                process.setFinalDecision("approved");
                process.setCompletedAt(LocalDateTime.now());
                processMapper.updateById(process);
            }
        } else {
            stageMapper.updateById(stage);
        }
    }

    private boolean evaluateStageCompletion(ApprovalStage stage, ApprovalProcess process) {
        String strategy = stage.getApprovalStrategy();
        int total = stage.getApproverCount();
        int approved = stage.getApprovedCount();
        int rejected = stage.getRejectedCount();
        int completed = approved + rejected;

        if ("any".equals(strategy)) {
            if (rejected > 0) {
                return true;
            }
            return approved >= 1;
        }

        if ("all".equals(strategy)) {
            if (rejected > 0) {
                return true;
            }
            return approved >= total;
        }

        if ("percentage".equals(strategy)) {
            if (completed < total) return false;
            BigDecimal percentage = process.getApproverList() != null ?
                    new BigDecimal("100") : new BigDecimal("50");
            BigDecimal actual = BigDecimal.valueOf(approved)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            return actual.compareTo(percentage) >= 0;
        }

        if ("sequential".equals(strategy)) {
            return completed >= 1;
        }

        return completed >= total;
    }

    public boolean evaluateCondition(String expression, Map<String, Object> formData,
                                     Map<String, Object> variables, Long starterId) {
        if (expression == null || expression.trim().isEmpty()) {
            return true;
        }

        String processedExpr = replaceVariables(expression, formData, variables, starterId);

        return evaluateSimpleExpression(processedExpr);
    }

    private String replaceVariables(String expression, Map<String, Object> formData,
                                     Map<String, Object> variables, Long starterId) {
        Matcher matcher = EXPRESSION_PATTERN.matcher(expression);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String varPath = matcher.group(1).trim();
            Object value = resolveVariable(varPath, formData, variables, starterId);
            String replacement = formatValueForExpression(value);
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private Object resolveVariable(String varPath, Map<String, Object> formData,
                                   Map<String, Object> variables, Long starterId) {
        if (varPath.startsWith("form.")) {
            String field = varPath.substring(5);
            return getNestedValue(formData, field);
        }

        if (varPath.startsWith("var.")) {
            String field = varPath.substring(4);
            return getNestedValue(variables, field);
        }

        if (varPath.startsWith("starter.")) {
            String field = varPath.substring(8);
            Employee starter = employeeMapper.selectById(starterId);
            if (starter == null) return null;

            switch (field) {
                case "id":
                    return starter.getId();
                case "name":
                    return starter.getName();
                case "department":
                    return starter.getDepartment();
                case "position":
                    return starter.getPosition();
                default:
                    if (starter.getAttributes() != null) {
                        return starter.getAttributes().get(field);
                    }
                    return null;
            }
        }

        return getNestedValue(variables, varPath);
    }

    private Object getNestedValue(Map<String, Object> map, String path) {
        if (map == null) return null;

        String[] parts = path.split("\\.");
        Object current = map;

        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }

        return current;
    }

    private String formatValueForExpression(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "'" + escapeString((String) value) + "'";
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return "'" + value.toString() + "'";
    }

    private String escapeString(String s) {
        return s.replace("'", "\\'").replace("\\", "\\\\");
    }

    private boolean evaluateSimpleExpression(String expr) {
        expr = expr.trim();

        if ("true".equalsIgnoreCase(expr)) return true;
        if ("false".equalsIgnoreCase(expr)) return false;

        Pattern andPattern = Pattern.compile("\\s+AND\\s+", Pattern.CASE_INSENSITIVE);
        Pattern orPattern = Pattern.compile("\\s+OR\\s+", Pattern.CASE_INSENSITIVE);

        String[] orParts = orPattern.split(expr);
        for (String orPart : orParts) {
            boolean andResult = true;
            String[] andParts = andPattern.split(orPart.trim());
            for (String andPart : andParts) {
                if (!evaluateSingleCondition(andPart.trim())) {
                    andResult = false;
                    break;
                }
            }
            if (andResult) return true;
        }
        return false;
    }

    private boolean evaluateSingleCondition(String condition) {
        if (condition.startsWith("(") && condition.endsWith(")")) {
            return evaluateSimpleExpression(condition.substring(1, condition.length() - 1));
        }

        String[] operators = {"!=", ">=", "<=", "==", ">", "<", "="};
        for (String op : operators) {
            int idx = condition.indexOf(op);
            if (idx > 0) {
                String left = condition.substring(0, idx).trim();
                String right = condition.substring(idx + op.length()).trim();

                Object leftVal = parseValue(left);
                Object rightVal = parseValue(right);

                return compareValues(leftVal, rightVal, op);
            }
        }

        if ("null".equalsIgnoreCase(condition) || condition.equals("''") || condition.equals("\"\"")) {
            return false;
        }

        try {
            return Boolean.parseBoolean(condition);
        } catch (Exception e) {
            return true;
        }
    }

    private Object parseValue(String val) {
        val = val.trim();

        if ("null".equalsIgnoreCase(val)) return null;
        if ("true".equalsIgnoreCase(val)) return true;
        if ("false".equalsIgnoreCase(val)) return false;

        if ((val.startsWith("'") && val.endsWith("'")) ||
                (val.startsWith("\"") && val.endsWith("\""))) {
            return val.substring(1, val.length() - 1);
        }

        try {
            if (val.contains(".")) {
                return new BigDecimal(val);
            }
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return val;
        }
    }

    private boolean compareValues(Object left, Object right, String op) {
        if ("==".equals(op) || "=".equals(op)) {
            return Objects.equals(left, right);
        }
        if ("!=".equals(op)) {
            return !Objects.equals(left, right);
        }

        if (left == null || right == null) {
            return false;
        }

        if (left instanceof Number && right instanceof Number) {
            BigDecimal leftNum = new BigDecimal(left.toString());
            BigDecimal rightNum = new BigDecimal(right.toString());
            int cmp = leftNum.compareTo(rightNum);

            switch (op) {
                case ">":
                    return cmp > 0;
                case ">=":
                    return cmp >= 0;
                case "<":
                    return cmp < 0;
                case "<=":
                    return cmp <= 0;
            }
        }

        if (left instanceof Comparable && right instanceof Comparable &&
                left.getClass().equals(right.getClass())) {
            int cmp = ((Comparable) left).compareTo(right);

            switch (op) {
                case ">":
                    return cmp > 0;
                case ">=":
                    return cmp >= 0;
                case "<":
                    return cmp < 0;
                case "<=":
                    return cmp <= 0;
            }
        }

        if ((left instanceof String || right instanceof String) && (">".equals(op) || "<".equals(op) || ">=".equals(op) || "<=".equals(op))) {
            int cmp = String.valueOf(left).compareTo(String.valueOf(right));
            switch (op) {
                case ">":
                    return cmp > 0;
                case ">=":
                    return cmp >= 0;
                case "<":
                    return cmp < 0;
                case "<=":
                    return cmp <= 0;
            }
        }

        return false;
    }

    public ApprovalProcess getProcess(Long id) {
        Long tenantId = TenantContext.getTenantId();
        ApprovalProcess process = processMapper.selectById(id);
        if (process == null || !process.getTenantId().equals(tenantId)) {
            throw new BusinessException("审批流程不存在");
        }

        List<ApprovalStage> stages = stageMapper.findByProcessId(id, tenantId);
        process.setStages(stages);

        List<ApprovalTask> tasks = taskMapper.findByProcessId(id, tenantId);
        enrichTasksWithApprover(tasks);
        process.setTasks(tasks);

        if (process.getStartedBy() != null) {
            Employee starter = employeeMapper.selectById(process.getStartedBy());
            process.setStarter(starter);
        }

        return process;
    }

    public Page<ApprovalProcess> listProcesses(int page, int size, String status, String businessType, Long starterId) {
        Long tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<ApprovalProcess> wrapper = new LambdaQueryWrapper<ApprovalProcess>()
                .eq(ApprovalProcess::getTenantId, tenantId);

        if (status != null) wrapper.eq(ApprovalProcess::getStatus, status);
        if (businessType != null) wrapper.eq(ApprovalProcess::getBusinessType, businessType);
        if (starterId != null) wrapper.eq(ApprovalProcess::getStartedBy, starterId);

        wrapper.orderByDesc(ApprovalProcess::getCreatedAt);
        Page<ApprovalProcess> pageResult = processMapper.selectPage(new Page<>(page, size), wrapper);

        for (ApprovalProcess process : pageResult.getRecords()) {
            List<ApprovalStage> stages = stageMapper.findByProcessId(process.getId(), tenantId);
            process.setStages(stages);
            if (process.getStartedBy() != null) {
                Employee starter = employeeMapper.selectById(process.getStartedBy());
                process.setStarter(starter);
            }
        }

        return pageResult;
    }

    public Page<ApprovalTask> listTasks(int page, int size, Long approverId, String status, Long processId) {
        Long tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<ApprovalTask> wrapper = new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getTenantId, tenantId);

        if (approverId != null) wrapper.eq(ApprovalTask::getApproverId, approverId);
        if (status != null) wrapper.eq(ApprovalTask::getStatus, status);
        if (processId != null) wrapper.eq(ApprovalTask::getProcessId, processId);

        wrapper.orderByDesc(ApprovalTask::getCreatedAt);
        Page<ApprovalTask> pageResult = taskMapper.selectPage(new Page<>(page, size), wrapper);
        enrichTasksWithApprover(pageResult.getRecords());
        return pageResult;
    }

    private void enrichTasksWithApprover(List<ApprovalTask> tasks) {
        Set<Long> approverIds = tasks.stream()
                .map(ApprovalTask::getApproverId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (approverIds.isEmpty()) return;

        Map<Long, Employee> employeeMap = employeeMapper.selectBatchIds(approverIds).stream()
                .collect(Collectors.toMap(Employee::getId, e -> e, (a, b) -> a));

        for (ApprovalTask task : tasks) {
            task.setApprover(employeeMap.get(task.getApproverId()));
        }
    }

    private List<ApprovalDTO.ApprovalTaskDTO> convertToTaskDTOs(List<ApprovalTask> tasks) {
        if (tasks == null) return Collections.emptyList();

        enrichTasksWithApprover(tasks);
        List<ApprovalDTO.ApprovalTaskDTO> dtos = new ArrayList<>();
        for (ApprovalTask task : tasks) {
            ApprovalDTO.ApprovalTaskDTO dto = new ApprovalDTO.ApprovalTaskDTO();
            dto.setTaskId(task.getId());
            dto.setApproverId(task.getApproverId());
            dto.setApproverName(task.getApprover() != null ? task.getApprover().getName() : null);
            dto.setStatus(task.getStatus());
            dto.setAction(task.getAction());
            dto.setComment(task.getComment());
            dto.setAssignedAt(task.getAssignedAt());
            dto.setActedAt(task.getActedAt());
            dtos.add(dto);
        }
        return dtos;
    }

    @Transactional
    public ApprovalProcess cancelProcess(ApprovalDTO.CancelProcessDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        ApprovalProcess process = getProcess(dto.getProcessId());

        if (!"approving".equals(process.getStatus())) {
            throw new BusinessException("当前状态不支持撤销");
        }

        process.setStatus("cancelled");
        process.setFinalDecision("cancelled");
        process.setFinalComment(dto.getReason());
        process.setCompletedAt(LocalDateTime.now());
        processMapper.updateById(process);

        return process;
    }

    @Transactional
    public List<ApprovalTask> addSign(ApprovalDTO.AddSignDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        ApprovalProcess process = getProcess(dto.getProcessId());

        ApprovalStage stage = dto.getStageId() != null ?
                stageMapper.selectById(dto.getStageId()) :
                process.getStages().stream()
                        .filter(s -> "approving".equals(s.getStatus()))
                        .findFirst()
                        .orElse(null);

        if (stage == null) {
            throw new BusinessException("没有可加签的阶段");
        }

        List<ApprovalTask> newTasks = new ArrayList<>();
        for (Long approverId : dto.getApproverIds()) {
            ApprovalTask task = new ApprovalTask();
            task.setId(IdUtil.getSnowflakeNextId());
            task.setTenantId(tenantId);
            task.setProcessId(process.getId());
            task.setStageId(stage.getId());
            task.setApproverId(approverId);
            task.setStatus("pending");
            task.setAssignedAt(LocalDateTime.now());
            taskMapper.insert(task);
            newTasks.add(task);
        }

        stage.setApproverCount(stage.getApproverCount() + dto.getApproverIds().size());
        stageMapper.updateById(stage);

        return newTasks;
    }

    public boolean evaluateCondition(ApprovalDTO.ConditionEvaluationDTO dto) {
        return evaluateCondition(dto.getExpression(), dto.getFormData(),
                dto.getVariables(), dto.getStarterId());
    }

    public List<Long> resolveDynamicApprovers(ApprovalDTO.DynamicApproverDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        TenantContext.setTenantId(tenantId);
        try {
            return resolveDynamicApprovers(dto.getStrategy(), dto.getFormData(),
                    dto.getVariables(), dto.getStarterId());
        } finally {
            TenantContext.clear();
        }
    }

    @Scheduled(fixedRate = 300000)
    @Transactional
    public void checkTimeoutProcesses() {
        log.info("Starting approval timeout check task at {}", LocalDateTime.now());
        try {
            List<ApprovalProcess> processes = processMapper.selectList(
                    new LambdaQueryWrapper<ApprovalProcess>()
                            .eq(ApprovalProcess::getStatus, "approving")
                            .isNotNull(ApprovalProcess::getTimeoutAt)
                            .le(ApprovalProcess::getTimeoutAt, LocalDateTime.now())
            );

            for (ApprovalProcess process : processes) {
                try {
                    TenantContext.setTenantId(process.getTenantId());
                    process.setStatus("timeout");
                    process.setFinalDecision("timeout");
                    process.setCompletedAt(LocalDateTime.now());
                    processMapper.updateById(process);
                    log.info("审批流程超时: {}", process.getProcessNo());
                } finally {
                    TenantContext.clear();
                }
            }
            log.info("Approval timeout check completed, processed {} processes", processes.size());
        } catch (Exception e) {
            log.error("Approval timeout check task failed", e);
        }
    }

    private void validateRuleCreate(ApprovalDTO.RuleCreateDTO dto, Long tenantId) {
        if (dto.getRuleCode() == null || dto.getRuleCode().trim().isEmpty()) {
            throw new ValidationException("规则编码不能为空");
        }
        if (dto.getRuleName() == null || dto.getRuleName().trim().isEmpty()) {
            throw new ValidationException("规则名称不能为空");
        }
        if (dto.getBusinessType() == null || dto.getBusinessType().trim().isEmpty()) {
            throw new ValidationException("业务类型不能为空");
        }

        LambdaQueryWrapper<ApprovalRule> wrapper = new LambdaQueryWrapper<ApprovalRule>()
                .eq(ApprovalRule::getTenantId, tenantId)
                .eq(ApprovalRule::getRuleCode, dto.getRuleCode());
        if (ruleMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("规则编码已存在");
        }
    }

    private void validateProcessStart(ApprovalDTO.ProcessStartDTO dto) {
        if (dto.getBusinessType() == null || dto.getBusinessType().trim().isEmpty()) {
            throw new ValidationException("业务类型不能为空");
        }
        if (dto.getTitle() == null || dto.getTitle().trim().isEmpty()) {
            throw new ValidationException("审批标题不能为空");
        }
        if (dto.getStartedBy() == null) {
            throw new ValidationException("发起人不能为空");
        }
    }
}
