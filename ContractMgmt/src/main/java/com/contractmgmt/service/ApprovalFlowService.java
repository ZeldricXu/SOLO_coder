package com.contractmgmt.service;

import com.contractmgmt.config.ContractConfig;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.exception.ContractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ApprovalFlowService {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalFlowService.class);

    private final ContractConfig contractConfig;

    public ApprovalFlowService(ContractConfig contractConfig) {
        this.contractConfig = contractConfig;
    }

    public FlowContext resolveApprovalFlow(Contract contract) {
        ContractConfig.ApprovalFlow approvalFlowConfig = contractConfig.getApproval().getApprovalFlow();
        if (approvalFlowConfig == null || approvalFlowConfig.getFlows() == null || approvalFlowConfig.getFlows().isEmpty()) {
            logger.debug("无配置化流程，使用默认审批人列表");
            return createDefaultFlowContext(contract);
        }

        Optional<ContractConfig.FlowConfig> matchedFlow = approvalFlowConfig.getFlows().stream()
                .filter(flow -> Boolean.TRUE.equals(flow.getEnabled()))
                .filter(flow -> matchCondition(contract, flow))
                .findFirst();

        if (matchedFlow.isPresent()) {
            ContractConfig.FlowConfig flow = matchedFlow.get();
            logger.info("匹配审批流程: {}, 条件: {}", flow.getName(), flow.getCondition());
            return createFlowContext(contract, flow);
        }

        logger.debug("未匹配到特定流程，使用默认流程: {}", approvalFlowConfig.getDefaultFlow());
        return createDefaultFlowContext(contract);
    }

    private boolean matchCondition(Contract contract, ContractConfig.FlowConfig flow) {
        if (flow.getCondition() == null || flow.getCondition().equals("default")) {
            return false;
        }

        String condition = flow.getCondition().trim().toLowerCase();

        if (condition.startsWith("amount>")) {
            try {
                BigDecimal threshold = new BigDecimal(condition.substring(7));
                return contract.getContractAmount() != null &&
                        contract.getContractAmount().compareTo(threshold) > 0;
            } catch (NumberFormatException e) {
                logger.warn("金额条件解析失败: {}", condition);
                return false;
            }
        }

        if (condition.startsWith("amount<")) {
            try {
                BigDecimal threshold = new BigDecimal(condition.substring(7));
                return contract.getContractAmount() != null &&
                        contract.getContractAmount().compareTo(threshold) < 0;
            } catch (NumberFormatException e) {
                logger.warn("金额条件解析失败: {}", condition);
                return false;
            }
        }

        if (condition.startsWith("type=")) {
            String type = condition.substring(5);
            return contract.getContractType() != null &&
                    contract.getContractType().equalsIgnoreCase(type);
        }

        if (condition.startsWith("urgency=")) {
            String urgency = condition.substring(8);
            return contract.getUrgencyLevel() != null &&
                    contract.getUrgencyLevel().equalsIgnoreCase(urgency);
        }

        if (condition.startsWith("urgency>=")) {
            String urgency = condition.substring(9);
            return isUrgencyGreaterOrEqual(contract.getUrgencyLevel(), urgency);
        }

        if (condition.equals("all")) {
            return true;
        }

        return false;
    }

    private boolean isUrgencyGreaterOrEqual(String contractUrgency, String targetUrgency) {
        if (contractUrgency == null) {
            contractUrgency = "normal";
        }

        int contractLevel = getUrgencyLevel(contractUrgency);
        int targetLevel = getUrgencyLevel(targetUrgency);

        return contractLevel >= targetLevel;
    }

    private int getUrgencyLevel(String urgency) {
        switch (urgency.toLowerCase()) {
            case "critical":
                return 3;
            case "urgent":
                return 2;
            case "normal":
            default:
                return 1;
        }
    }

    private FlowContext createFlowContext(Contract contract, ContractConfig.FlowConfig flowConfig) {
        List<String> approvers = flowConfig.getApprovers();
        if (approvers == null || approvers.isEmpty()) {
            throw new ContractException(400, "审批流程 " + flowConfig.getName() + " 未配置审批人员");
        }

        FlowContext context = new FlowContext();
        context.setFlowName(flowConfig.getName());
        context.setDescription(flowConfig.getDescription());
        context.setApprovers(new ArrayList<>(approvers));
        context.setPrimaryApprover(approvers.get(0));
        context.setAllApprovers(approvers.size() > 1);
        return context;
    }

    private FlowContext createDefaultFlowContext(Contract contract) {
        List<String> defaultApprovers = contractConfig.getApproval().getDefaultApprovers();

        FlowContext context = new FlowContext();
        context.setFlowName("default");
        context.setDescription("默认审批流程");

        if (defaultApprovers == null || defaultApprovers.isEmpty()) {
            context.setApprovers(new ArrayList<>());
            context.setPrimaryApprover(null);
            context.setAllApprovers(false);
        } else {
            context.setApprovers(new ArrayList<>(defaultApprovers));
            context.setPrimaryApprover(defaultApprovers.get(0));
            context.setAllApprovers(defaultApprovers.size() > 1);
        }

        return context;
    }

    public List<ContractConfig.FlowConfig> getAllFlowConfigs() {
        ContractConfig.ApprovalFlow approvalFlowConfig = contractConfig.getApproval().getApprovalFlow();
        if (approvalFlowConfig == null || approvalFlowConfig.getFlows() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(approvalFlowConfig.getFlows());
    }

    public Optional<ContractConfig.FlowConfig> getFlowConfigByName(String name) {
        return getAllFlowConfigs().stream()
                .filter(f -> name.equals(f.getName()))
                .findFirst();
    }

    public boolean isValidApprover(String approver, Contract contract) {
        FlowContext flowContext = resolveApprovalFlow(contract);
        return flowContext.getApprovers().contains(approver);
    }

    public static class FlowContext {
        private String flowName;
        private String description;
        private List<String> approvers;
        private String primaryApprover;
        private boolean allApprovers;

        public String getFlowName() {
            return flowName;
        }

        public void setFlowName(String flowName) {
            this.flowName = flowName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<String> getApprovers() {
            return approvers;
        }

        public void setApprovers(List<String> approvers) {
            this.approvers = approvers;
        }

        public String getPrimaryApprover() {
            return primaryApprover;
        }

        public void setPrimaryApprover(String primaryApprover) {
            this.primaryApprover = primaryApprover;
        }

        public boolean isAllApprovers() {
            return allApprovers;
        }

        public void setAllApprovers(boolean allApprovers) {
            this.allApprovers = allApprovers;
        }
    }
}
