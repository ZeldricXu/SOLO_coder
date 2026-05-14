package com.contractmgmt.builder;

import com.contractmgmt.entity.*;
import com.contractmgmt.dto.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class TestDataBuilder {

    public static final String TEST_CONTRACT_ID = "contract_20260510_000001";
    public static final String TEST_APPROVER = "user_manager_01";
    public static final String TEST_APPROVER_VALID = "user_manager_01";
    public static final String TEST_APPROVER_INVALID = "user_unauthorized";
    public static final String TEST_OPERATOR = "user_operator_01";

    public enum ContractUrgency {
        NORMAL("normal"),
        URGENT("urgent"),
        CRITICAL("critical");

        private final String value;

        ContractUrgency(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public static String generateContractId() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return "contract_" + dateStr + "_" + suffix;
    }

    public static String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
    }

    public static Contract buildContract() {
        return buildContract(ContractUrgency.NORMAL);
    }

    public static Contract buildContract(ContractUrgency urgency) {
        Contract contract = new Contract();
        contract.setContractId(generateContractId());
        contract.setContractName("采购合同-" + urgency.getValue());
        contract.setContractType("purchase");
        contract.setUrgencyLevel(urgency.getValue());
        contract.setContractAmount(new BigDecimal("100000.00"));
        contract.setContractStart(LocalDate.now());
        contract.setContractEnd(LocalDate.now().plusMonths(6));
        contract.setPartyA("采购公司");
        contract.setPartyB("供应商有限公司");
        contract.setContractStatus("pending_approval");
        contract.setExecutionProgress(0);
        contract.setExecutionStatus("pending");
        contract.setActivityLevel("normal");
        contract.setCreatedAt(LocalDateTime.now());
        return contract;
    }

    public static Contract buildPendingApprovalContract() {
        Contract contract = buildContract();
        contract.setContractStatus("pending_approval");
        return contract;
    }

    public static Contract buildApprovedContract() {
        Contract contract = buildContract();
        contract.setContractStatus("approved");
        contract.setEffectiveTime(LocalDateTime.now());
        contract.setExecutionProgress(0);
        contract.setExecutionStatus("in_progress");
        return contract;
    }

    public static Contract buildRejectedContract() {
        Contract contract = buildContract();
        contract.setContractStatus("rejected");
        return contract;
    }

    public static Contract buildActiveContract() {
        Contract contract = buildContract();
        contract.setContractStatus("approved");
        contract.setEffectiveTime(LocalDateTime.now().minusDays(30));
        contract.setExecutionProgress(50);
        contract.setExecutionStatus("in_progress");
        return contract;
    }

    public static Contract buildCompletedContract() {
        Contract contract = buildContract();
        contract.setContractStatus("approved");
        contract.setEffectiveTime(LocalDateTime.now().minusDays(60));
        contract.setExecutionProgress(100);
        contract.setExecutionStatus("completed");
        return contract;
    }

    public static Contract buildArchivedContract() {
        Contract contract = buildContract();
        contract.setContractStatus("archived");
        contract.setArchiveTime(LocalDateTime.now());
        return contract;
    }

    public static Contract buildExpiringContract(int daysToExpire) {
        Contract contract = buildContract();
        contract.setContractStatus("approved");
        contract.setContractEnd(LocalDate.now().plusDays(daysToExpire));
        contract.setEffectiveTime(LocalDateTime.now().minusDays(30));
        contract.setExecutionProgress(30);
        contract.setExecutionStatus("in_progress");
        return contract;
    }

    public static Contract buildExpiredContract() {
        Contract contract = buildContract();
        contract.setContractStatus("approved");
        contract.setContractEnd(LocalDate.now().minusDays(1));
        contract.setEffectiveTime(LocalDateTime.now().minusDays(180));
        contract.setExecutionProgress(100);
        contract.setExecutionStatus("completed");
        return contract;
    }

    public static ApprovalRecord buildApprovalRecord(String contractId, String status) {
        ApprovalRecord record = new ApprovalRecord();
        record.setApprovalId(generateId("approval"));
        record.setContractId(contractId);
        record.setApprovalType("create");
        record.setApprovalStatus(status);
        record.setApprover(TEST_APPROVER_VALID);
        record.setApprovalComment(status.equals("approved") ? "审批通过" : "审批拒绝");
        record.setApprovalTime(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    public static ApprovalRecord buildPendingApprovalRecord(String contractId) {
        ApprovalRecord record = buildApprovalRecord(contractId, "pending");
        record.setApprovalComment("待审批");
        return record;
    }

    public static ApprovalRecord buildApprovedApprovalRecord(String contractId) {
        return buildApprovalRecord(contractId, "approved");
    }

    public static ApprovalRecord buildRejectedApprovalRecord(String contractId) {
        return buildApprovalRecord(contractId, "rejected");
    }

    public static ApprovalRecord buildTimeoutApprovalRecord(String contractId, int hoursAgo) {
        ApprovalRecord record = buildPendingApprovalRecord(contractId);
        record.setApprovalTime(LocalDateTime.now().minusHours(hoursAgo));
        return record;
    }

    public static ExecutionRecord buildExecutionRecord(String contractId, int progress) {
        ExecutionRecord record = new ExecutionRecord();
        record.setExecutionId(generateId("execution"));
        record.setContractId(contractId);
        record.setExecutionType("payment");
        record.setExecutionAmount(new BigDecimal("50000.00"));
        record.setExecutionProgress(progress);
        record.setExecutionDescription("执行进度记录: " + progress + "%");
        record.setExecutionTime(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    public static ExecutionRecord buildPartialExecutionRecord(String contractId) {
        return buildExecutionRecord(contractId, 50);
    }

    public static ExecutionRecord buildFullExecutionRecord(String contractId) {
        return buildExecutionRecord(contractId, 100);
    }

    public static ReminderConfig buildExpireReminder(String contractId, LocalDate reminderDate) {
        ReminderConfig reminder = new ReminderConfig();
        reminder.setReminderId(generateId("reminder"));
        reminder.setContractId(contractId);
        reminder.setReminderType("expire");
        reminder.setReminderTime(reminderDate);
        reminder.setReminderChannel("email");
        reminder.setReminderStatus("pending");
        reminder.setRetryCount(0);
        reminder.setCreatedAt(LocalDateTime.now());
        return reminder;
    }

    public static ReminderConfig buildPendingReminder(String contractId) {
        return buildExpireReminder(contractId, LocalDate.now());
    }

    public static ReminderConfig buildSentReminder(String contractId) {
        ReminderConfig reminder = buildPendingReminder(contractId);
        reminder.setReminderStatus("sent");
        reminder.setSentTime(LocalDateTime.now());
        return reminder;
    }

    public static ReminderConfig buildFailedReminder(String contractId) {
        ReminderConfig reminder = buildPendingReminder(contractId);
        reminder.setReminderStatus("failed");
        reminder.setRetryCount(2);
        return reminder;
    }

    public static ReminderConfig buildMultiChannelReminder(String contractId, String channel) {
        ReminderConfig reminder = buildPendingReminder(contractId);
        reminder.setReminderChannel(channel);
        return reminder;
    }

    public static ChangeRecord buildChangeRecord(String contractId, String changeType) {
        ChangeRecord record = new ChangeRecord();
        record.setChangeId(generateId("change"));
        record.setContractId(contractId);
        record.setChangeType(changeType);
        record.setChangeBefore(new BigDecimal("100000.00"));
        record.setChangeAfter(new BigDecimal("120000.00"));
        record.setChangeReason("合同变更测试");
        record.setChangeStatus("pending");
        record.setChangeTime(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    public static RenewalRecord buildRenewalRecord(String originalContractId) {
        RenewalRecord record = new RenewalRecord();
        record.setRenewalId(generateId("renewal"));
        record.setContractId(generateContractId());
        record.setOriginalContractId(originalContractId);
        record.setRenewalAmount(new BigDecimal("100000.00"));
        record.setRenewalStart(LocalDate.now().plusDays(1));
        record.setRenewalEnd(LocalDate.now().plusMonths(12));
        record.setRenewalReason("合同续签测试");
        record.setRenewalStatus("pending");
        record.setRenewalTime(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    public static ArchiveRecord buildArchiveRecord(String contractId) {
        ArchiveRecord record = new ArchiveRecord();
        record.setArchiveId(generateId("archive"));
        record.setContractId(contractId);
        record.setArchiveLocation("/contracts/archive/" + contractId);
        record.setArchiveReason("合同到期归档");
        record.setArchiveOperator(TEST_OPERATOR);
        record.setArchiveTime(LocalDateTime.now());
        record.setContractSnapshot("{\"contractId\":\"" + contractId + "\"}");
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    public static ContractHistory buildHistoryRecord(String contractId, String type, String action) {
        ContractHistory history = new ContractHistory();
        history.setHistoryId(generateId("history"));
        history.setContractId(contractId);
        history.setHistoryType(type);
        history.setAction(action);
        history.setOperator(TEST_OPERATOR);
        history.setDetail("历史记录: " + action);
        history.setActionTime(LocalDateTime.now());
        history.setCreatedAt(LocalDateTime.now());
        return history;
    }

    public static ContractStat buildContractStat(String month) {
        ContractStat stat = new ContractStat();
        stat.setStatId(generateId("stat"));
        stat.setStatMonth(month);
        stat.setTotalCount(100);
        stat.setActiveCount(80);
        stat.setArchivedCount(10);
        stat.setRejectedCount(5);
        stat.setPendingCount(5);
        stat.setTotalAmount(new BigDecimal("10000000.00"));
        stat.setActiveAmount(new BigDecimal("8000000.00"));
        stat.setCreatedAt(LocalDateTime.now());
        return stat;
    }

    public static CreateContractRequest buildCreateContractRequest() {
        return buildCreateContractRequest("采购合同-测试", new BigDecimal("100000.00"));
    }

    public static CreateContractRequest buildCreateContractRequest(String name, BigDecimal amount) {
        CreateContractRequest request = new CreateContractRequest();
        request.setContractName(name);
        request.setContractType("purchase");
        request.setContractAmount(amount);
        request.setContractStart(LocalDate.now());
        request.setContractEnd(LocalDate.now().plusMonths(6));
        request.setPartyA("采购公司");
        request.setPartyB("供应商有限公司");
        request.setOperator(TEST_OPERATOR);
        return request;
    }

    public static CreateContractRequest buildInvalidAmountRequest() {
        CreateContractRequest request = buildCreateContractRequest();
        request.setContractAmount(new BigDecimal("-100.00"));
        return request;
    }

    public static CreateContractRequest buildInvalidDateRequest() {
        CreateContractRequest request = buildCreateContractRequest();
        request.setContractStart(LocalDate.now().plusDays(10));
        request.setContractEnd(LocalDate.now());
        return request;
    }

    public static ApprovalRequest buildApprovalRequest(String contractId, String status) {
        ApprovalRequest request = new ApprovalRequest();
        request.setContractId(contractId);
        request.setApprovalStatus(status);
        request.setApprover(TEST_APPROVER_VALID);
        request.setApprovalComment(status.equals("approved") ? "审批通过" : "审批拒绝");
        request.setApprovalType("create");
        return request;
    }

    public static ApprovalRequest buildApproveRequest(String contractId) {
        return buildApprovalRequest(contractId, "approved");
    }

    public static ApprovalRequest buildRejectRequest(String contractId) {
        return buildApprovalRequest(contractId, "rejected");
    }

    public static ApprovalRequest buildInvalidApproverRequest(String contractId) {
        ApprovalRequest request = buildApproveRequest(contractId);
        request.setApprover(TEST_APPROVER_INVALID);
        return request;
    }

    public static ExecutionRequest buildExecutionRequest(String contractId, int progress) {
        ExecutionRequest request = new ExecutionRequest();
        request.setContractId(contractId);
        request.setExecutionType("payment");
        request.setExecutionAmount(new BigDecimal("50000.00"));
        request.setExecutionProgress(progress);
        request.setExecutionDescription("执行记录");
        request.setOperator(TEST_OPERATOR);
        return request;
    }

    public static ExecutionRequest buildPartialExecutionRequest(String contractId) {
        return buildExecutionRequest(contractId, 50);
    }

    public static ExecutionRequest buildFullExecutionRequest(String contractId) {
        return buildExecutionRequest(contractId, 100);
    }

    public static ExecutionRequest buildInvalidProgressRequest(String contractId) {
        ExecutionRequest request = buildExecutionRequest(contractId, 50);
        request.setExecutionProgress(150);
        return request;
    }

    public static ChangeRequest buildChangeRequest(String contractId) {
        ChangeRequest request = new ChangeRequest();
        request.setContractId(contractId);
        request.setChangeType("amount");
        request.setChangeBefore(new BigDecimal("100000.00"));
        request.setChangeAfter(new BigDecimal("120000.00"));
        request.setChangeReason("合同变更测试");
        request.setOperator(TEST_OPERATOR);
        return request;
    }

    public static RenewalRequest buildRenewalRequest(String originalContractId) {
        RenewalRequest request = new RenewalRequest();
        request.setOriginalContractId(originalContractId);
        request.setRenewalAmount(new BigDecimal("100000.00"));
        request.setRenewalStart(LocalDate.now().plusDays(1));
        request.setRenewalEnd(LocalDate.now().plusMonths(12));
        request.setRenewalReason("合同续签测试");
        request.setOperator(TEST_OPERATOR);
        return request;
    }
}
