package com.restaurant.mgmt.service;

import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.Employee;
import com.restaurant.mgmt.model.Order;
import com.restaurant.mgmt.repository.EmployeeRepository;
import com.restaurant.mgmt.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class EmployeeService {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private HistoryService historyService;

    public Employee createEmployee(Employee employee) {
        if (employee.getEmployeeName() == null || employee.getEmployeeName().trim().isEmpty()) {
            throw new BusinessException("员工姓名不能为空");
        }
        
        employee.setEmployeeId(IdGenerator.generateEmployeeId());
        employee.setCreatedAt(LocalDateTime.now());
        employee.setUpdatedAt(LocalDateTime.now());
        if (employee.getStatus() == null) {
            employee.setStatus("active");
        }
        
        Employee saved = employeeRepository.save(employee);
        historyService.recordHistory("employee", saved.getEmployeeId(), "创建员工", 
            "创建员工: " + saved.getEmployeeName(), "system", "create", "success");
        
        return saved;
    }

    public Employee updateEmployee(String employeeId, Employee employee) {
        Optional<Employee> existingOpt = employeeRepository.findById(employeeId);
        if (existingOpt.isEmpty()) {
            throw new BusinessException("员工不存在");
        }
        
        Employee existing = existingOpt.get();
        if (employee.getEmployeeName() != null) {
            existing.setEmployeeName(employee.getEmployeeName());
        }
        if (employee.getPosition() != null) {
            existing.setPosition(employee.getPosition());
        }
        if (employee.getDepartment() != null) {
            existing.setDepartment(employee.getDepartment());
        }
        if (employee.getPhone() != null) {
            existing.setPhone(employee.getPhone());
        }
        if (employee.getEmail() != null) {
            existing.setEmail(employee.getEmail());
        }
        if (employee.getGender() != null) {
            existing.setGender(employee.getGender());
        }
        if (employee.getHireDate() != null) {
            existing.setHireDate(employee.getHireDate());
        }
        if (employee.getStatus() != null) {
            existing.setStatus(employee.getStatus());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        
        return employeeRepository.save(existing);
    }

    public void deleteEmployee(String employeeId) {
        if (!employeeRepository.existsById(employeeId)) {
            throw new BusinessException("员工不存在");
        }
        employeeRepository.deleteById(employeeId);
    }

    public Employee getEmployeeById(String employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new BusinessException("员工不存在"));
    }

    public List<Employee> getAllEmployees() {
        return employeeRepository.findAll();
    }

    public List<Employee> getEmployeesByDepartment(String department) {
        return employeeRepository.findByDepartment(department);
    }

    public List<Employee> getEmployeesByPosition(String position) {
        return employeeRepository.findByPosition(position);
    }

    public List<Employee> getActiveEmployees() {
        return employeeRepository.findByStatus("active");
    }

    @Transactional
    public Employee activateEmployee(String employeeId) {
        Employee employee = getEmployeeById(employeeId);
        employee.setStatus("active");
        employee.setUpdatedAt(LocalDateTime.now());
        return employeeRepository.save(employee);
    }

    @Transactional
    public Employee deactivateEmployee(String employeeId) {
        Employee employee = getEmployeeById(employeeId);
        employee.setStatus("inactive");
        employee.setUpdatedAt(LocalDateTime.now());
        return employeeRepository.save(employee);
    }

    public void notifyWaiters(Order order) {
        List<Employee> waiters = employeeRepository.findByPosition("waiter");
        for (Employee waiter : waiters) {
            System.out.printf("通知服务员 %s: 新订单 %s, 桌位: %s, 金额: %.2f%n",
                waiter.getEmployeeName(), order.getOrderId(), 
                order.getTableNumber(), order.getOrderAmount());
        }
        
        historyService.recordHistory("notification", order.getOrderId(), "订单通知", 
            "已通知服务员处理订单: " + order.getOrderId(), "system", "notify", "success");
    }
}
