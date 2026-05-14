package com.crm.service;

import com.crm.common.IdGenerator;
import com.crm.dto.CustomerRequest;
import com.crm.entity.Customer;
import com.crm.exception.BusinessException;
import com.crm.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CustomerService {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private CategoryMatchingWorker categoryMatchingWorker;

    @Autowired
    private CustomerValueService customerValueService;

    @Autowired
    private CustomerTypeService customerTypeService;

    @Transactional
    public Map<String, Object> createCustomer(CustomerRequest request) {
        String customerType = request.getCustomerType();
        
        if (customerType != null && !customerTypeService.isValidType(customerType)) {
            throw new BusinessException("无效的客户类型: " + customerType);
        }
        
        if (customerType == null) {
            customerType = customerTypeService.getDefaultTypeCode();
        }

        Customer customer = new Customer();
        customer.setCustomerId(IdGenerator.generateCustomerId());
        customer.setCustomerName(request.getCustomerName());
        customer.setCustomerType(customerType);
        customer.setCustomerStatus("potential");
        customer.setCustomerSource(request.getCustomerSource() != null ? request.getCustomerSource() : "marketing");
        customer.setCustomerContact(request.getCustomerContact());
        customer.setCustomerAddress(request.getCustomerAddress());
        
        Customer savedCustomer = customerRepository.save(customer);
        
        analysisService.incrementCustomerCount();
        
        historyService.recordHistory(
                savedCustomer.getCustomerId(),
                "customer",
                savedCustomer.getCustomerId(),
                "create",
                "创建客户：" + savedCustomer.getCustomerName() + "，类型：" + customerTypeService.getTypeName(customerType),
                request.getCustomerName()
        );

        CustomerValueService.CustomerValue value = customerValueService.evaluateCustomerValue(savedCustomer);
        categoryMatchingWorker.submitTask(savedCustomer.getCustomerId(), value.name());
        log.debug("客户创建成功，分类任务已提交: customerId={}, value={}", savedCustomer.getCustomerId(), value.name());

        Map<String, Object> result = new HashMap<>();
        result.put("customer_id", savedCustomer.getCustomerId());
        result.put("status", savedCustomer.getCustomerStatus());
        result.put("customer_type", savedCustomer.getCustomerType());
        result.put("customer_value", value.name());
        return result;
    }

    public Customer getCustomerById(String customerId) {
        return customerRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new BusinessException("客户不存在"));
    }

    public List<Customer> getAllCustomers() {
        return customerRepository.findAll();
    }

    public List<Customer> getCustomersByStatus(String status) {
        return customerRepository.findByCustomerStatus(status);
    }

    public List<Customer> getCustomersByType(String type) {
        if (!customerTypeService.isValidType(type)) {
            throw new BusinessException("无效的客户类型: " + type);
        }
        return customerRepository.findByCustomerType(type);
    }

    @Transactional
    public Customer updateCustomerStatus(String customerId, String newStatus) {
        Customer customer = getCustomerById(customerId);
        
        String currentStatus = customer.getCustomerStatus();
        if ("deal".equals(currentStatus) || "closed".equals(currentStatus)) {
            throw new BusinessException("客户已成交，无法更新状态");
        }
        
        String oldStatus = customer.getCustomerStatus();
        customer.setCustomerStatus(newStatus);
        Customer updated = customerRepository.save(customer);
        
        historyService.recordHistory(
                customerId,
                "customer",
                customerId,
                "update_status",
                "客户状态从 " + oldStatus + " 更新为 " + newStatus,
                null
        );
        
        if ("closed".equals(newStatus) || "deal".equals(newStatus)) {
            CustomerValueService.CustomerValue value = customerValueService.evaluateCustomerValue(updated);
            categoryMatchingWorker.submitTask(customerId, value.name());
            log.debug("客户状态变更为成交，重新提交分类任务: customerId={}", customerId);
        }
        
        return updated;
    }

    @Transactional
    public void incrementFollowCount(String customerId) {
        Customer customer = getCustomerById(customerId);
        customer.setFollowCount(customer.getFollowCount() + 1);
        customerRepository.save(customer);
    }

    @Transactional
    public void incrementOpportunityCount(String customerId) {
        Customer customer = getCustomerById(customerId);
        customer.setOpportunityCount(customer.getOpportunityCount() + 1);
        customerRepository.save(customer);
    }

    @Transactional
    public Customer updateCustomer(String customerId, CustomerRequest request) {
        Customer customer = getCustomerById(customerId);
        
        boolean typeChanged = false;
        String oldType = customer.getCustomerType();
        
        if (request.getCustomerName() != null) {
            customer.setCustomerName(request.getCustomerName());
        }
        if (request.getCustomerType() != null) {
            if (!customerTypeService.isValidType(request.getCustomerType())) {
                throw new BusinessException("无效的客户类型: " + request.getCustomerType());
            }
            customer.setCustomerType(request.getCustomerType());
            typeChanged = !request.getCustomerType().equals(oldType);
        }
        if (request.getCustomerSource() != null) {
            customer.setCustomerSource(request.getCustomerSource());
        }
        if (request.getCustomerContact() != null) {
            customer.setCustomerContact(request.getCustomerContact());
        }
        if (request.getCustomerAddress() != null) {
            customer.setCustomerAddress(request.getCustomerAddress());
        }
        
        Customer updated = customerRepository.save(customer);
        
        historyService.recordHistory(
                customerId,
                "customer",
                customerId,
                "update",
                "更新客户信息" + (typeChanged ? "（类型变更：" + oldType + " → " + updated.getCustomerType() + "）" : ""),
                null
        );
        
        if (typeChanged) {
            CustomerValueService.CustomerValue value = customerValueService.evaluateCustomerValue(updated);
            categoryMatchingWorker.submitTask(customerId, value.name());
            log.debug("客户类型变更，重新提交分类任务: customerId={}, oldType={}, newType={}", 
                    customerId, oldType, updated.getCustomerType());
        }
        
        return updated;
    }

    public CustomerValueService.CustomerValue evaluateCustomerValue(String customerId) {
        Customer customer = getCustomerById(customerId);
        return customerValueService.evaluateCustomerValue(customer);
    }

    public Map<String, Object> getCustomerInfoWithMetadata(String customerId) {
        Customer customer = getCustomerById(customerId);
        Map<String, Object> result = new HashMap<>();
        result.put("customer", customer);
        
        String typeName = customerTypeService.getTypeName(customer.getCustomerType());
        result.put("customer_type_name", typeName);
        
        CustomerValueService.CustomerValue value = evaluateCustomerValue(customerId);
        result.put("customer_value", value.name());
        result.put("customer_value_description", value.getDescription());
        
        return result;
    }
}
