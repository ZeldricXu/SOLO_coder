package com.contractai.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.contractai.common.context.TenantContext;
import com.contractai.common.dto.PageQuery;
import com.contractai.common.dto.PageResult;
import com.contractai.common.exception.BusinessException;
import com.contractai.common.exception.ValidationException;
import com.contractai.skill.dto.*;
import com.contractai.skill.entity.*;
import com.contractai.skill.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillCategoryMapper skillCategoryMapper;
    private final SkillMapper skillMapper;
    private final EmployeeMapper employeeMapper;
    private final EmployeeSkillMapper employeeSkillMapper;
    private final LearningPathMapper learningPathMapper;
    private final SkillMatchCalculator skillMatchCalculator;

    @Transactional(rollbackFor = Exception.class)
    public SkillCategory createCategory(SkillCategoryCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateCategoryCreate(dto);

        SkillCategory category = buildSkillCategory(dto, tenantId);
        skillCategoryMapper.insert(category);
        log.info("创建技能分类成功: tenantId={}, code={}", tenantId, dto.getCategoryCode());
        return category;
    }

    public List<SkillCategory> listCategories() {
        Long tenantId = TenantContext.getTenantId();
        return skillCategoryMapper.selectList(
                new LambdaQueryWrapper<SkillCategory>()
                        .eq(SkillCategory::getTenantId, tenantId)
                        .orderByAsc(SkillCategory::getSortOrder, SkillCategory::getCategoryCode));
    }

    public List<SkillCategory> getCategoryTree() {
        List<SkillCategory> all = listCategories();
        return buildCategoryTree(all);
    }

    @Transactional(rollbackFor = Exception.class)
    public Skill createSkill(SkillCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateSkillCreate(dto);

        Skill skill = buildSkill(dto, tenantId);
        skillMapper.insert(skill);
        log.info("创建技能成功: tenantId={}, code={}", tenantId, dto.getSkillCode());
        return skill;
    }

    public PageResult<Skill> listSkills(PageQuery query) {
        Long tenantId = TenantContext.getTenantId();
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Skill> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(query.getPageNum(), query.getPageSize());

        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Skill::getTenantId, tenantId)
                .orderByDesc(Skill::getCreatedAt);

        skillMapper.selectPage(page, wrapper);
        return new PageResult<>(page.getTotal(), page.getRecords(), query.getPageNum(), query.getPageSize());
    }

    public Skill getSkill(Long id) {
        Skill skill = skillMapper.selectById(id);
        if (skill == null || skill.getDeleted() == 1) {
            throw new BusinessException(404, "技能不存在");
        }
        return skill;
    }

    @Transactional(rollbackFor = Exception.class)
    public Employee createEmployee(EmployeeCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateEmployeeCreate(dto);
        checkEmployeeNoUnique(tenantId, dto.getEmployeeNo());

        Employee employee = buildEmployee(dto, tenantId);
        employeeMapper.insert(employee);
        log.info("创建员工成功: tenantId={}, employeeNo={}", tenantId, dto.getEmployeeNo());
        return employee;
    }

    public PageResult<Employee> listEmployees(PageQuery query) {
        Long tenantId = TenantContext.getTenantId();
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Employee> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(query.getPageNum(), query.getPageSize());

        LambdaQueryWrapper<Employee> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Employee::getTenantId, tenantId)
                .orderByDesc(Employee::getCreatedAt);

        employeeMapper.selectPage(page, wrapper);
        return new PageResult<>(page.getTotal(), page.getRecords(), query.getPageNum(), query.getPageSize());
    }

    @Transactional(rollbackFor = Exception.class)
    public EmployeeSkill createEmployeeSkill(EmployeeSkillCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateEmployeeSkillCreate(dto);
        checkEmployeeSkillUnique(tenantId, dto.getEmployeeId(), dto.getSkillId());

        EmployeeSkill employeeSkill = buildEmployeeSkill(dto, tenantId);
        employeeSkillMapper.insert(employeeSkill);
        log.info("创建员工技能关联成功: employeeId={}, skillId={}", dto.getEmployeeId(), dto.getSkillId());
        return employeeSkill;
    }

    public List<EmployeeSkill> getEmployeeSkills(Long employeeId) {
        Long tenantId = TenantContext.getTenantId();
        return employeeSkillMapper.findByEmployeeId(tenantId, employeeId);
    }

    @Transactional(rollbackFor = Exception.class)
    public EmployeeSkill assessSkill(SkillAssessmentDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        EmployeeSkill employeeSkill = findOrCreateEmployeeSkill(dto, tenantId);
        updateSkillAssessment(employeeSkill, dto);

        log.info("技能评估完成: employeeId={}, skillId={}, level={}",
                dto.getEmployeeId(), dto.getSkillId(), dto.getProficiencyLevel());
        return employeeSkill;
    }

    public Map<String, Object> getEmployeeSkillMatrix(Long employeeId) {
        List<EmployeeSkill> employeeSkills = getEmployeeSkills(employeeId);
        return buildSkillMatrix(employeeId, employeeSkills);
    }

    @Transactional(rollbackFor = Exception.class)
    public LearningPath createLearningPath(LearningPathCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateLearningPathCreate(dto);

        LearningPath path = buildLearningPath(dto, tenantId);
        learningPathMapper.insert(path);
        log.info("创建学习路径成功: tenantId={}, pathCode={}", tenantId, dto.getPathCode());
        return path;
    }

    public List<LearningPath> recommendLearningPaths(LearningRecommendationDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        Set<Long> masteredSkills = getMasteredSkills(dto.getEmployeeId());

        List<LearningPath> allPaths = findPathsByTargetSkill(tenantId, dto.getTargetSkillId());
        return filterRecommendablePaths(allPaths, masteredSkills);
    }

    public Map<Long, BigDecimal> calculateSkillMatchScore(List<Long> requiredSkillIds, Long employeeId) {
        List<EmployeeSkill> employeeSkills = getEmployeeSkills(employeeId);
        Map<Long, EmployeeSkill> skillMap = employeeSkills.stream()
                .collect(Collectors.toMap(EmployeeSkill::getSkillId, es -> es, (a, b) -> a));

        Map<Long, BigDecimal> scores = new HashMap<>();
        for (Long skillId : requiredSkillIds) {
            scores.put(skillId, skillMatchCalculator.calculateScore(skillMap.get(skillId)));
        }
        return scores;
    }

    public List<Map<String, Object>> findEmployeesBySkills(List<Long> skillIds, BigDecimal minMatchScore) {
        Long tenantId = TenantContext.getTenantId();
        List<Employee> allEmployees = employeeMapper.findAllByTenantId(tenantId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Employee employee : allEmployees) {
            Map<Long, BigDecimal> skillScores = calculateSkillMatchScore(skillIds, employee.getId());
            BigDecimal avgScore = skillMatchCalculator.calculateAverageScore(skillScores);

            if (minMatchScore == null || avgScore.compareTo(minMatchScore) >= 0) {
                result.add(buildEmployeeMatchResult(employee, skillScores, avgScore));
            }
        }

        result.sort((a, b) -> ((BigDecimal) b.get("averageMatchScore"))
                .compareTo((BigDecimal) a.get("averageMatchScore")));

        return result;
    }

    private List<SkillCategory> buildCategoryTree(List<SkillCategory> all) {
        Map<Long, List<SkillCategory>> childrenMap = all.stream()
                .collect(Collectors.groupingBy(SkillCategory::getParentId));

        all.forEach(cat -> cat.setChildren(childrenMap.getOrDefault(cat.getId(), Collections.emptyList())));

        return all.stream()
                .filter(cat -> cat.getParentId() == 0)
                .collect(Collectors.toList());
    }

    private void validateCategoryCreate(SkillCategoryCreateDTO dto) {
        if (!StringUtils.hasText(dto.getCategoryCode())) {
            throw new ValidationException("分类编码不能为空");
        }
        if (!StringUtils.hasText(dto.getCategoryName())) {
            throw new ValidationException("分类名称不能为空");
        }
    }

    private SkillCategory buildSkillCategory(SkillCategoryCreateDTO dto, Long tenantId) {
        SkillCategory category = new SkillCategory();
        category.setTenantId(tenantId);
        category.setCategoryCode(dto.getCategoryCode());
        category.setCategoryName(dto.getCategoryName());
        category.setParentId(dto.getParentId() != null ? dto.getParentId() : 0L);
        category.setLevel(dto.getParentId() != null ? 2 : 1);
        category.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
        category.setDescription(dto.getDescription());
        return category;
    }

    private void validateSkillCreate(SkillCreateDTO dto) {
        if (!StringUtils.hasText(dto.getSkillCode())) {
            throw new ValidationException("技能编码不能为空");
        }
        if (!StringUtils.hasText(dto.getSkillName())) {
            throw new ValidationException("技能名称不能为空");
        }
        if (dto.getCategoryId() == null) {
            throw new ValidationException("请选择技能分类");
        }
    }

    private Skill buildSkill(SkillCreateDTO dto, Long tenantId) {
        Skill skill = new Skill();
        skill.setTenantId(tenantId);
        skill.setSkillCode(dto.getSkillCode());
        skill.setSkillName(dto.getSkillName());
        skill.setCategoryId(dto.getCategoryId());
        skill.setLevel(dto.getLevel() != null ? dto.getLevel() : 1);
        skill.setDescription(dto.getDescription());
        skill.setPrerequisiteSkills(dto.getPrerequisiteSkills());
        skill.setLearningPath(dto.getLearningPath());
        skill.setCertificationRequired(dto.getCertificationRequired() != null && dto.getCertificationRequired() ? 1 : 0);
        return skill;
    }

    private void validateEmployeeCreate(EmployeeCreateDTO dto) {
        if (!StringUtils.hasText(dto.getEmployeeNo())) {
            throw new ValidationException("员工编号不能为空");
        }
        if (!StringUtils.hasText(dto.getName())) {
            throw new ValidationException("员工姓名不能为空");
        }
    }

    private void checkEmployeeNoUnique(Long tenantId, String employeeNo) {
        Employee exists = employeeMapper.selectOne(
                new LambdaQueryWrapper<Employee>()
                        .eq(Employee::getTenantId, tenantId)
                        .eq(Employee::getEmployeeNo, employeeNo));
        if (exists != null) {
            throw new BusinessException(400, "员工编号已存在");
        }
    }

    private Employee buildEmployee(EmployeeCreateDTO dto, Long tenantId) {
        Employee employee = new Employee();
        employee.setTenantId(tenantId);
        employee.setEmployeeNo(dto.getEmployeeNo());
        employee.setName(dto.getName());
        employee.setDepartment(dto.getDepartment());
        employee.setPosition(dto.getPosition());
        employee.setEmail(dto.getEmail());
        employee.setPhone(dto.getPhone());
        employee.setAttributes(dto.getAttributes());
        return employee;
    }

    private void validateEmployeeSkillCreate(EmployeeSkillCreateDTO dto) {
        if (dto.getEmployeeId() == null) {
            throw new ValidationException("请选择员工");
        }
        if (dto.getSkillId() == null) {
            throw new ValidationException("请选择技能");
        }
    }

    private void checkEmployeeSkillUnique(Long tenantId, Long employeeId, Long skillId) {
        EmployeeSkill exists = employeeSkillMapper.selectOne(
                new LambdaQueryWrapper<EmployeeSkill>()
                        .eq(EmployeeSkill::getTenantId, tenantId)
                        .eq(EmployeeSkill::getEmployeeId, employeeId)
                        .eq(EmployeeSkill::getSkillId, skillId));
        if (exists != null) {
            throw new BusinessException(400, "该员工技能已存在");
        }
    }

    private EmployeeSkill buildEmployeeSkill(EmployeeSkillCreateDTO dto, Long tenantId) {
        EmployeeSkill employeeSkill = new EmployeeSkill();
        employeeSkill.setTenantId(tenantId);
        employeeSkill.setEmployeeId(dto.getEmployeeId());
        employeeSkill.setSkillId(dto.getSkillId());
        employeeSkill.setProficiencyLevel(dto.getProficiencyLevel() != null ? dto.getProficiencyLevel() : 1);
        employeeSkill.setCertificationStatus(dto.getCertificationStatus() != null ? dto.getCertificationStatus() : 0);
        employeeSkill.setCertificationDate(dto.getCertificationDate());
        employeeSkill.setExpireDate(dto.getExpireDate());
        employeeSkill.setAssessmentScore(dto.getAssessmentScore());
        employeeSkill.setLastAssessedAt(LocalDateTime.now());
        return employeeSkill;
    }

    private EmployeeSkill findOrCreateEmployeeSkill(SkillAssessmentDTO dto, Long tenantId) {
        EmployeeSkill employeeSkill = employeeSkillMapper.selectOne(
                new LambdaQueryWrapper<EmployeeSkill>()
                        .eq(EmployeeSkill::getTenantId, tenantId)
                        .eq(EmployeeSkill::getEmployeeId, dto.getEmployeeId())
                        .eq(EmployeeSkill::getSkillId, dto.getSkillId()));

        if (employeeSkill == null) {
            employeeSkill = new EmployeeSkill();
            employeeSkill.setTenantId(tenantId);
            employeeSkill.setEmployeeId(dto.getEmployeeId());
            employeeSkill.setSkillId(dto.getSkillId());
            employeeSkill.setCertificationStatus(0);
            employeeSkillMapper.insert(employeeSkill);
        }
        return employeeSkill;
    }

    private void updateSkillAssessment(EmployeeSkill employeeSkill, SkillAssessmentDTO dto) {
        employeeSkill.setProficiencyLevel(dto.getProficiencyLevel());
        employeeSkill.setAssessmentScore(dto.getAssessmentScore());
        employeeSkill.setLastAssessedAt(LocalDateTime.now());
        employeeSkillMapper.updateById(employeeSkill);
    }

    private Map<String, Object> buildSkillMatrix(Long employeeId, List<EmployeeSkill> employeeSkills) {
        Map<Long, Skill> skillMap = loadSkillMap(employeeSkills);

        List<Map<String, Object>> skills = employeeSkills.stream()
                .map(es -> buildSkillDetail(es, skillMap))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("employeeId", employeeId);
        result.put("skills", skills);
        result.put("totalSkills", skills.size());
        result.put("averageScore", calculateAverageScore(employeeSkills));

        return result;
    }

    private Map<Long, Skill> loadSkillMap(List<EmployeeSkill> employeeSkills) {
        List<Long> skillIds = employeeSkills.stream()
                .map(EmployeeSkill::getSkillId)
                .collect(Collectors.toList());

        if (skillIds.isEmpty()) {
            return Collections.emptyMap();
        }

        return skillMapper.selectBatchIds(skillIds).stream()
                .collect(Collectors.toMap(Skill::getId, s -> s, (a, b) -> a));
    }

    private Map<String, Object> buildSkillDetail(EmployeeSkill es, Map<Long, Skill> skillMap) {
        Map<String, Object> map = new HashMap<>();
        Skill skill = skillMap.get(es.getSkillId());
        if (skill != null) {
            map.put("skillId", skill.getId());
            map.put("skillCode", skill.getSkillCode());
            map.put("skillName", skill.getSkillName());
            map.put("skillLevel", skill.getLevel());
        }
        map.put("proficiencyLevel", es.getProficiencyLevel());
        map.put("assessmentScore", es.getAssessmentScore());
        map.put("certificationStatus", es.getCertificationStatus());
        map.put("lastAssessedAt", es.getLastAssessedAt());
        return map;
    }

    private BigDecimal calculateAverageScore(List<EmployeeSkill> employeeSkills) {
        double avgScore = employeeSkills.stream()
                .filter(es -> es.getAssessmentScore() != null)
                .mapToDouble(es -> es.getAssessmentScore().doubleValue())
                .average()
                .orElse(0.0);
        return BigDecimal.valueOf(avgScore).setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private void validateLearningPathCreate(LearningPathCreateDTO dto) {
        if (!StringUtils.hasText(dto.getPathCode())) {
            throw new ValidationException("路径编码不能为空");
        }
        if (!StringUtils.hasText(dto.getPathName())) {
            throw new ValidationException("路径名称不能为空");
        }
        if (dto.getTargetSkillId() == null) {
            throw new ValidationException("请选择目标技能");
        }
    }

    private LearningPath buildLearningPath(LearningPathCreateDTO dto, Long tenantId) {
        LearningPath path = new LearningPath();
        path.setTenantId(tenantId);
        path.setPathCode(dto.getPathCode());
        path.setPathName(dto.getPathName());
        path.setDescription(dto.getDescription());
        path.setTargetSkillId(dto.getTargetSkillId());
        path.setEstimatedHours(dto.getEstimatedHours());
        path.setCourseSteps(dto.getCourseSteps());
        path.setPrerequisitePaths(dto.getPrerequisitePaths());
        return path;
    }

    private Set<Long> getMasteredSkills(Long employeeId) {
        return getEmployeeSkills(employeeId).stream()
                .filter(es -> es.getProficiencyLevel() >= 3)
                .map(EmployeeSkill::getSkillId)
                .collect(Collectors.toSet());
    }

    private List<LearningPath> findPathsByTargetSkill(Long tenantId, Long targetSkillId) {
        return learningPathMapper.selectList(
                new LambdaQueryWrapper<LearningPath>()
                        .eq(LearningPath::getTenantId, tenantId)
                        .eq(targetSkillId != null, LearningPath::getTargetSkillId, targetSkillId));
    }

    private List<LearningPath> filterRecommendablePaths(List<LearningPath> allPaths, Set<Long> masteredSkills) {
        return allPaths.stream()
                .filter(path -> !masteredSkills.contains(path.getTargetSkillId()))
                .filter(path -> path.getPrerequisitePaths() == null
                        || masteredSkills.containsAll(path.getPrerequisitePaths()))
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildEmployeeMatchResult(Employee employee, Map<Long, BigDecimal> skillScores,
                                                          BigDecimal avgScore) {
        Map<String, Object> emp = new HashMap<>();
        emp.put("employeeId", employee.getId());
        emp.put("employeeNo", employee.getEmployeeNo());
        emp.put("name", employee.getName());
        emp.put("department", employee.getDepartment());
        emp.put("position", employee.getPosition());
        emp.put("matchScores", skillScores);
        emp.put("averageMatchScore", avgScore);
        return emp;
    }
}
