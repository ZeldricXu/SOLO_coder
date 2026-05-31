package com.smartflow.ticketassignment.service;

import com.smartflow.common.dto.AssignmentRequest;
import com.smartflow.common.dto.AssignmentResult;
import com.smartflow.common.exception.BusinessException;
import com.smartflow.common.utils.IdGenerator;
import com.smartflow.persistence.entity.Employee;
import com.smartflow.persistence.entity.EmployeeSkill;
import com.smartflow.persistence.entity.Ticket;
import com.smartflow.persistence.entity.TicketAssignmentLog;
import com.smartflow.persistence.mapper.EmployeeMapper;
import com.smartflow.persistence.mapper.EmployeeSkillMapper;
import com.smartflow.persistence.mapper.TicketAssignmentLogMapper;
import com.smartflow.persistence.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketAssignmentService {

    private final TicketMapper ticketMapper;
    private final EmployeeMapper employeeMapper;
    private final EmployeeSkillMapper employeeSkillMapper;
    private final TicketAssignmentLogMapper assignmentLogMapper;

    @Transactional
    public AssignmentResult assignTicket(AssignmentRequest request) {
        Ticket ticket = ticketMapper.selectById(request.getTicketId());
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        List<Employee> candidates = findEligibleEmployees(request);
        if (candidates.isEmpty()) {
            throw new BusinessException("没有找到合适的处理人");
        }

        Employee bestMatch = selectBestCandidate(candidates, request);

        AssignmentResult result = buildAssignmentResult(bestMatch, request);
        updateTicketAssignment(ticket, bestMatch, result);
        recordAssignmentLog(ticket, bestMatch, result);
        updateEmployeeLoad(bestMatch);

        return result;
    }

    private List<Employee> findEligibleEmployees(AssignmentRequest request) {
        List<Employee> availableEmployees = employeeMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Employee>()
                .eq(Employee::getAvailable, 1)
                .apply("current_load < max_load")
        );

        if (availableEmployees.isEmpty()) {
            return Collections.emptyList();
        }

        if (request.getRequiredSkills() == null || request.getRequiredSkills().isEmpty()) {
            return availableEmployees;
        }

        Set<String> requiredSkillSet = new HashSet<>(Arrays.asList(request.getRequiredSkills().split(",")));

        return availableEmployees.stream()
            .filter(emp -> hasRequiredSkills(emp.getId(), requiredSkillSet))
            .collect(Collectors.toList());
    }

    private boolean hasRequiredSkills(Long employeeId, Set<String> requiredSkills) {
        List<EmployeeSkill> skills = employeeSkillMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EmployeeSkill>()
                .eq(EmployeeSkill::getEmployeeId, employeeId)
                .ge(EmployeeSkill::getProficiency, 3)
        );

        Set<String> employeeSkills = skills.stream()
            .map(EmployeeSkill::getSkillName)
            .collect(Collectors.toSet());

        return employeeSkills.containsAll(requiredSkills);
    }

    private Employee selectBestCandidate(List<Employee> candidates, AssignmentRequest request) {
        Map<Long, Integer> skillScores = calculateSkillScores(candidates, request.getRequiredSkills());

        return candidates.stream()
            .max(Comparator.comparingInt((Employee e) -> skillScores.getOrDefault(e.getId(), 0))
                .thenComparingInt(e -> -(e.getCurrentLoad() * 100 / Math.max(e.getMaxLoad(), 1))))
            .orElse(candidates.get(0));
    }

    private Map<Long, Integer> calculateSkillScores(List<Employee> employees, String requiredSkills) {
        Map<Long, Integer> scores = new HashMap<>();
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            employees.forEach(e -> scores.put(e.getId(), 100));
            return scores;
        }

        Set<String> requiredSkillSet = new HashSet<>(Arrays.asList(requiredSkills.split(",")));

        for (Employee employee : employees) {
            List<EmployeeSkill> skills = employeeSkillMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EmployeeSkill>()
                    .eq(EmployeeSkill::getEmployeeId, employee.getId())
            );

            int matchCount = 0;
            int totalProficiency = 0;
            for (EmployeeSkill skill : skills) {
                if (requiredSkillSet.contains(skill.getSkillName())) {
                    matchCount++;
                    totalProficiency += skill.getProficiency();
                }
            }

            double skillMatchRatio = (double) matchCount / requiredSkillSet.size();
            double avgProficiency = matchCount > 0 ? (double) totalProficiency / matchCount / 5.0 : 0;
            int score = (int) ((skillMatchRatio * 0.7 + avgProficiency * 0.3) * 100);
            scores.put(employee.getId(), score);
        }

        return scores;
    }

    private AssignmentResult buildAssignmentResult(Employee employee, AssignmentRequest request) {
        AssignmentResult result = new AssignmentResult();
        result.setAssigneeId(employee.getId());
        result.setAssigneeName(employee.getName());
        result.setCurrentLoad(employee.getCurrentLoad() + 1);
        result.setAssignedAt(LocalDateTime.now());
        result.setMatchScore(calculateMatchScore(employee, request.getRequiredSkills()));
        result.setReason("技能匹配度: " + result.getMatchScore() + "%, 当前负载: " + employee.getCurrentLoad());
        return result;
    }

    private Integer calculateMatchScore(Employee employee, String requiredSkills) {
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return 100;
        }
        Set<String> requiredSkillSet = new HashSet<>(Arrays.asList(requiredSkills.split(",")));
        List<EmployeeSkill> skills = employeeSkillMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EmployeeSkill>()
                .eq(EmployeeSkill::getEmployeeId, employee.getId())
        );
        Set<String> employeeSkills = skills.stream()
            .map(EmployeeSkill::getSkillName)
            .collect(Collectors.toSet());
        int matchCount = 0;
        for (String skill : requiredSkillSet) {
            if (employeeSkills.contains(skill)) {
                matchCount++;
            }
        }
        return (int) ((double) matchCount / requiredSkillSet.size() * 100);
    }

    private void updateTicketAssignment(Ticket ticket, Employee employee, AssignmentResult result) {
        ticket.setAssigneeId(employee.getId());
        ticket.setAssigneeName(employee.getName());
        ticket.setStatus(1);
        ticketMapper.updateById(ticket);
    }

    private void recordAssignmentLog(Ticket ticket, Employee employee, AssignmentResult result) {
        TicketAssignmentLog log = new TicketAssignmentLog();
        log.setId(IdGenerator.generateId());
        log.setTicketId(ticket.getId());
        log.setFromAssigneeId(ticket.getAssigneeId());
        log.setFromAssigneeName(ticket.getAssigneeName());
        log.setToAssigneeId(employee.getId());
        log.setToAssigneeName(employee.getName());
        log.setMatchScore(result.getMatchScore());
        log.setLoadBefore(employee.getCurrentLoad());
        log.setLoadAfter(employee.getCurrentLoad() + 1);
        log.setReason(result.getReason());
        log.setAssignedAt(LocalDateTime.now());
        assignmentLogMapper.insert(log);
    }

    private void updateEmployeeLoad(Employee employee) {
        employee.setCurrentLoad(employee.getCurrentLoad() + 1);
        employeeMapper.updateById(employee);
    }

    @Transactional
    public boolean reassignTicket(Long ticketId, String reason) {
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }

        Employee currentAssignee = employeeMapper.selectById(ticket.getAssigneeId());
        if (currentAssignee != null) {
            currentAssignee.setCurrentLoad(Math.max(0, currentAssignee.getCurrentLoad() - 1));
            employeeMapper.updateById(currentAssignee);
        }

        AssignmentRequest request = new AssignmentRequest();
        request.setTicketId(ticketId);
        request.setRequiredSkills(ticket.getRequiredSkills());
        request.setPriority(ticket.getPriority());
        request.setCurrentAssigneeId(ticket.getAssigneeId());

        assignTicket(request);
        return true;
    }

    public Map<String, Object> getEmployeeLoadStatus() {
        List<Employee> employees = employeeMapper.selectList(null);
        Map<String, Object> result = new HashMap<>();
        result.put("totalEmployees", employees.size());
        result.put("availableCount", employees.stream().filter(e -> e.getAvailable() == 1).count());
        result.put("overloadedCount", employees.stream().filter(e -> e.getCurrentLoad() >= e.getMaxLoad()).count());
        result.put("avgLoad", employees.stream().mapToInt(Employee::getCurrentLoad).average().orElse(0));
        result.put("employees", employees);
        return result;
    }
}
