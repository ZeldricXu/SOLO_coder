package com.formflow.builder;

import com.formflow.entity.*;
import com.formflow.enums.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static String generateUniqueId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public static FormTemplate buildBasicFormTemplate() {
        String templateId = "template_" + generateUniqueId();
        return buildFormTemplate(templateId, "测试表单模板");
    }

    public static FormTemplate buildFormTemplate(String templateId, String templateName) {
        FormTemplate template = new FormTemplate();
        template.setTemplateId(templateId);
        template.setTemplateName(templateName);
        template.setDescription("测试用表单模板");
        template.setProcessDefinitionId("process_test_" + generateUniqueId());
        template.setEnabled(true);
        template.setVersion(1);
        template.setCreatorId("test_user");
        template.setCreatorName("测试用户");
        template.setCreatedAt(LocalDateTime.now());
        template.setUpdatedAt(LocalDateTime.now());

        List<FormTemplateField> fields = new ArrayList<>();

        FormTemplateField field1 = new FormTemplateField();
        field1.setFieldId("leave_type");
        field1.setFieldName("请假类型");
        field1.setFieldType(FieldType.SELECT);
        field1.setRequired(true);
        field1.setOptions("[\"事假\",\"病假\",\"年假\"]");
        field1.setSortOrder(1);
        fields.add(field1);

        FormTemplateField field2 = new FormTemplateField();
        field2.setFieldId("leave_days");
        field2.setFieldName("请假天数");
        field2.setFieldType(FieldType.NUMBER);
        field2.setRequired(true);
        field2.setSortOrder(2);
        fields.add(field2);

        FormTemplateField field3 = new FormTemplateField();
        field3.setFieldId("leave_reason");
        field3.setFieldName("请假原因");
        field3.setFieldType(FieldType.TEXTAREA);
        field3.setRequired(true);
        field3.setSortOrder(3);
        fields.add(field3);

        template.setFields(fields);
        return template;
    }

    public static FormData buildBasicFormData() {
        String formId = "form_" + generateUniqueId();
        return buildFormData(formId, "template_test", "user_001", "测试用户");
    }

    public static FormData buildFormData(String formId, String templateId,
                                          String submitterId, String submitterName) {
        FormData formData = new FormData();
        formData.setFormId(formId);
        formData.setTemplateId(templateId);
        formData.setSubmitterId(submitterId);
        formData.setSubmitterName(submitterName);
        formData.setFormData("{\"leave_type\":\"事假\",\"leave_days\":3,\"leave_reason\":\"测试请假\"}");
        formData.setStatus(FormStatus.PENDING_APPROVAL);
        formData.setProcessInstanceId("instance_" + generateUniqueId());
        formData.setCurrentApproverIds("user_manager_01");
        formData.setSubmitTime(LocalDateTime.now());
        formData.setUpdatedAt(LocalDateTime.now());
        return formData;
    }

    public static ProcessDefinition buildBasicProcessDefinition() {
        String processId = "process_" + generateUniqueId();
        return buildSimpleApprovalProcess(processId, "测试审批流程");
    }

    public static ProcessDefinition buildSimpleApprovalProcess(String processId, String processName) {
        ProcessDefinition definition = new ProcessDefinition();
        definition.setProcessId(processId);
        definition.setProcessName(processName);
        definition.setDescription("测试用审批流程");
        definition.setStartNodeId("start");
        definition.setEndNodeId("end");
        definition.setVersion(1);
        definition.setEnabled(true);
        definition.setCreatorId("test_user");
        definition.setCreatorName("测试用户");
        definition.setCreatedAt(LocalDateTime.now());
        definition.setUpdatedAt(LocalDateTime.now());

        List<ProcessNode> nodes = new ArrayList<>();

        ProcessNode startNode = new ProcessNode();
        startNode.setNodeId("start");
        startNode.setNodeName("开始");
        startNode.setNodeType(NodeType.START);
        startNode.setSortOrder(0);
        nodes.add(startNode);

        ProcessNode approvalNode = new ProcessNode();
        approvalNode.setNodeId("node_approval");
        approvalNode.setNodeName("审批节点");
        approvalNode.setNodeType(NodeType.APPROVAL);
        approvalNode.setApproverRole("manager");
        approvalNode.setApproverType("role");
        approvalNode.setSortOrder(1);
        nodes.add(approvalNode);

        ProcessNode endNode = new ProcessNode();
        endNode.setNodeId("end");
        endNode.setNodeName("结束");
        endNode.setNodeType(NodeType.END);
        endNode.setSortOrder(2);
        nodes.add(endNode);

        definition.setNodes(nodes);

        List<ProcessTransition> transitions = new ArrayList<>();

        ProcessTransition t1 = new ProcessTransition();
        t1.setFromNode("start");
        t1.setToNode("node_approval");
        t1.setCondition("always");
        t1.setSortOrder(1);
        transitions.add(t1);

        ProcessTransition t2 = new ProcessTransition();
        t2.setFromNode("node_approval");
        t2.setToNode("end");
        t2.setCondition("approved");
        t2.setSortOrder(2);
        transitions.add(t2);

        definition.setTransitions(transitions);
        return definition;
    }

    public static ProcessDefinition buildMultiApproverProcess(boolean isOrSign) {
        String processId = "process_multi_" + generateUniqueId();
        ProcessDefinition definition = new ProcessDefinition();
        definition.setProcessId(processId);
        definition.setProcessName("多人审批流程");
        definition.setDescription(isOrSign ? "或签测试流程" : "会签测试流程");
        definition.setStartNodeId("start");
        definition.setEndNodeId("end");
        definition.setVersion(1);
        definition.setEnabled(true);
        definition.setCreatorId("test_user");
        definition.setCreatorName("测试用户");
        definition.setCreatedAt(LocalDateTime.now());
        definition.setUpdatedAt(LocalDateTime.now());

        List<ProcessNode> nodes = new ArrayList<>();

        ProcessNode startNode = new ProcessNode();
        startNode.setNodeId("start");
        startNode.setNodeName("开始");
        startNode.setNodeType(NodeType.START);
        startNode.setSortOrder(0);
        nodes.add(startNode);

        ProcessNode managerNode = new ProcessNode();
        managerNode.setNodeId("node_manager");
        managerNode.setNodeName("部门经理审批");
        managerNode.setNodeType(NodeType.APPROVAL);
        managerNode.setApproverUserIds("user_manager_01,user_manager_02");
        managerNode.setApproverType("user");
        managerNode.setApprovalStrategy(isOrSign ? "OR" : "AND");
        managerNode.setSortOrder(1);
        nodes.add(managerNode);

        ProcessNode hrNode = new ProcessNode();
        hrNode.setNodeId("node_hr");
        hrNode.setNodeName("HR审批");
        hrNode.setNodeType(NodeType.APPROVAL);
        hrNode.setApproverUserIds("user_hr_01,user_hr_02,user_hr_03");
        hrNode.setApproverType("user");
        hrNode.setApprovalStrategy(isOrSign ? "OR" : "AND");
        hrNode.setSortOrder(2);
        nodes.add(hrNode);

        ProcessNode endNode = new ProcessNode();
        endNode.setNodeId("end");
        endNode.setNodeName("结束");
        endNode.setNodeType(NodeType.END);
        endNode.setSortOrder(3);
        nodes.add(endNode);

        definition.setNodes(nodes);

        List<ProcessTransition> transitions = new ArrayList<>();

        ProcessTransition t1 = new ProcessTransition();
        t1.setFromNode("start");
        t1.setToNode("node_manager");
        t1.setCondition("always");
        t1.setSortOrder(1);
        transitions.add(t1);

        ProcessTransition t2 = new ProcessTransition();
        t2.setFromNode("node_manager");
        t2.setToNode("node_hr");
        t2.setCondition("approved");
        t2.setSortOrder(2);
        transitions.add(t2);

        ProcessTransition t3 = new ProcessTransition();
        t3.setFromNode("node_hr");
        t3.setToNode("end");
        t3.setCondition("approved");
        t3.setSortOrder(3);
        transitions.add(t3);

        definition.setTransitions(transitions);
        return definition;
    }

    public static ProcessDefinition buildConditionalProcess() {
        String processId = "process_conditional_" + generateUniqueId();
        ProcessDefinition definition = new ProcessDefinition();
        definition.setProcessId(processId);
        definition.setProcessName("条件流转流程");
        definition.setDescription("根据条件走不同审批路径的测试流程");
        definition.setStartNodeId("start");
        definition.setEndNodeId("end");
        definition.setVersion(1);
        definition.setEnabled(true);
        definition.setCreatorId("test_user");
        definition.setCreatorName("测试用户");
        definition.setCreatedAt(LocalDateTime.now());
        definition.setUpdatedAt(LocalDateTime.now());

        List<ProcessNode> nodes = new ArrayList<>();

        ProcessNode startNode = new ProcessNode();
        startNode.setNodeId("start");
        startNode.setNodeName("开始");
        startNode.setNodeType(NodeType.START);
        startNode.setSortOrder(0);
        nodes.add(startNode);

        ProcessNode conditionNode = new ProcessNode();
        conditionNode.setNodeId("node_condition");
        conditionNode.setNodeName("条件判断");
        conditionNode.setNodeType(NodeType.CONDITION);
        conditionNode.setConditionExpression("leave_days > 3");
        conditionNode.setSortOrder(1);
        nodes.add(conditionNode);

        ProcessNode managerNode = new ProcessNode();
        managerNode.setNodeId("node_manager");
        managerNode.setNodeName("部门经理审批");
        managerNode.setNodeType(NodeType.APPROVAL);
        managerNode.setApproverRole("manager");
        managerNode.setApproverType("role");
        managerNode.setSortOrder(2);
        nodes.add(managerNode);

        ProcessNode directorNode = new ProcessNode();
        directorNode.setNodeId("node_director");
        directorNode.setNodeName("总监审批");
        directorNode.setNodeType(NodeType.APPROVAL);
        directorNode.setApproverRole("director");
        directorNode.setApproverType("role");
        directorNode.setSortOrder(3);
        nodes.add(directorNode);

        ProcessNode endNode = new ProcessNode();
        endNode.setNodeId("end");
        endNode.setNodeName("结束");
        endNode.setNodeType(NodeType.END);
        endNode.setSortOrder(4);
        nodes.add(endNode);

        definition.setNodes(nodes);

        List<ProcessTransition> transitions = new ArrayList<>();

        ProcessTransition t1 = new ProcessTransition();
        t1.setFromNode("start");
        t1.setToNode("node_condition");
        t1.setCondition("always");
        t1.setSortOrder(1);
        transitions.add(t1);

        ProcessTransition t2 = new ProcessTransition();
        t2.setFromNode("node_condition");
        t2.setToNode("node_manager");
        t2.setCondition("false");
        t2.setConditionExpression("leave_days <= 3");
        t2.setSortOrder(2);
        transitions.add(t2);

        ProcessTransition t3 = new ProcessTransition();
        t3.setFromNode("node_condition");
        t3.setToNode("node_director");
        t3.setCondition("true");
        t3.setConditionExpression("leave_days > 3");
        t3.setSortOrder(3);
        transitions.add(t3);

        ProcessTransition t4 = new ProcessTransition();
        t4.setFromNode("node_manager");
        t4.setToNode("end");
        t4.setCondition("approved");
        t4.setSortOrder(4);
        transitions.add(t4);

        ProcessTransition t5 = new ProcessTransition();
        t5.setFromNode("node_director");
        t5.setToNode("end");
        t5.setCondition("approved");
        t5.setSortOrder(5);
        transitions.add(t5);

        definition.setTransitions(transitions);
        return definition;
    }

    public static ProcessDefinition buildNestedConditionalProcess() {
        String processId = "process_nested_" + generateUniqueId();
        ProcessDefinition definition = new ProcessDefinition();
        definition.setProcessId(processId);
        definition.setProcessName("嵌套条件流程");
        definition.setDescription("复杂嵌套条件测试流程");
        definition.setStartNodeId("start");
        definition.setEndNodeId("end");
        definition.setVersion(1);
        definition.setEnabled(true);
        definition.setCreatorId("test_user");
        definition.setCreatorName("测试用户");
        definition.setCreatedAt(LocalDateTime.now());
        definition.setUpdatedAt(LocalDateTime.now());

        List<ProcessNode> nodes = new ArrayList<>();

        ProcessNode startNode = new ProcessNode();
        startNode.setNodeId("start");
        startNode.setNodeName("开始");
        startNode.setNodeType(NodeType.START);
        startNode.setSortOrder(0);
        nodes.add(startNode);

        ProcessNode condition1 = new ProcessNode();
        condition1.setNodeId("cond1");
        condition1.setNodeName("条件1-请假类型");
        condition1.setNodeType(NodeType.CONDITION);
        condition1.setConditionExpression("leave_type == '病假'");
        condition1.setSortOrder(1);
        nodes.add(condition1);

        ProcessNode condition2 = new ProcessNode();
        condition2.setNodeId("cond2");
        condition2.setNodeName("条件2-病假天数");
        condition2.setNodeType(NodeType.CONDITION);
        condition2.setConditionExpression("leave_days > 7");
        condition2.setSortOrder(2);
        nodes.add(condition2);

        ProcessNode hrNode = new ProcessNode();
        hrNode.setNodeId("node_hr");
        hrNode.setNodeName("HR审批");
        hrNode.setNodeType(NodeType.APPROVAL);
        hrNode.setApproverRole("hr");
        hrNode.setApproverType("role");
        hrNode.setSortOrder(3);
        nodes.add(hrNode);

        ProcessNode managerNode = new ProcessNode();
        managerNode.setNodeId("node_manager");
        managerNode.setNodeName("经理审批");
        managerNode.setNodeType(NodeType.APPROVAL);
        managerNode.setApproverRole("manager");
        managerNode.setApproverType("role");
        managerNode.setSortOrder(4);
        nodes.add(managerNode);

        ProcessNode directorNode = new ProcessNode();
        directorNode.setNodeId("node_director");
        directorNode.setNodeName("总监审批");
        directorNode.setNodeType(NodeType.APPROVAL);
        directorNode.setApproverRole("director");
        directorNode.setApproverType("role");
        directorNode.setSortOrder(5);
        nodes.add(directorNode);

        ProcessNode endNode = new ProcessNode();
        endNode.setNodeId("end");
        endNode.setNodeName("结束");
        endNode.setNodeType(NodeType.END);
        endNode.setSortOrder(6);
        nodes.add(endNode);

        definition.setNodes(nodes);

        List<ProcessTransition> transitions = new ArrayList<>();

        ProcessTransition t1 = new ProcessTransition();
        t1.setFromNode("start");
        t1.setToNode("cond1");
        t1.setCondition("always");
        t1.setSortOrder(1);
        transitions.add(t1);

        ProcessTransition t2 = new ProcessTransition();
        t2.setFromNode("cond1");
        t2.setToNode("cond2");
        t2.setCondition("true");
        t2.setConditionExpression("leave_type == '病假'");
        t2.setSortOrder(2);
        transitions.add(t2);

        ProcessTransition t3 = new ProcessTransition();
        t3.setFromNode("cond1");
        t3.setToNode("node_manager");
        t3.setCondition("false");
        t3.setConditionExpression("leave_type != '病假'");
        t3.setSortOrder(3);
        transitions.add(t3);

        ProcessTransition t4 = new ProcessTransition();
        t4.setFromNode("cond2");
        t4.setToNode("node_director");
        t4.setCondition("true");
        t4.setConditionExpression("leave_days > 7");
        t4.setSortOrder(4);
        transitions.add(t4);

        ProcessTransition t5 = new ProcessTransition();
        t5.setFromNode("cond2");
        t5.setToNode("node_hr");
        t5.setCondition("false");
        t5.setConditionExpression("leave_days <= 7");
        t5.setSortOrder(5);
        transitions.add(t5);

        ProcessTransition t6 = new ProcessTransition();
        t6.setFromNode("node_hr");
        t6.setToNode("end");
        t6.setCondition("approved");
        t6.setSortOrder(6);
        transitions.add(t6);

        ProcessTransition t7 = new ProcessTransition();
        t7.setFromNode("node_manager");
        t7.setToNode("end");
        t7.setCondition("approved");
        t7.setSortOrder(7);
        transitions.add(t7);

        ProcessTransition t8 = new ProcessTransition();
        t8.setFromNode("node_director");
        t8.setToNode("end");
        t8.setCondition("approved");
        t8.setSortOrder(8);
        transitions.add(t8);

        definition.setTransitions(transitions);
        return definition;
    }

    public static ProcessInstance buildBasicProcessInstance() {
        String instanceId = "instance_" + generateUniqueId();
        ProcessInstance instance = new ProcessInstance();
        instance.setInstanceId(instanceId);
        instance.setProcessId("process_test");
        instance.setFormId("form_test_001");
        instance.setCurrentNodeId("node_approval");
        instance.setPreviousNodeId("start");
        instance.setInstanceStatus(ProcessInstanceStatus.RUNNING);
        instance.setSubmitterId("user_001");
        instance.setSubmitterName("测试用户");
        instance.setVariables("{\"leave_days\":3}");
        instance.setStartTime(LocalDateTime.now());
        instance.setUpdatedAt(LocalDateTime.now());
        return instance;
    }

    public static ApprovalTask buildBasicApprovalTask() {
        String taskId = "task_" + generateUniqueId();
        return buildApprovalTask(taskId, "instance_test", "node_manager",
                "user_manager_01", "部门经理", TaskStatus.PENDING);
    }

    public static ApprovalTask buildApprovalTask(String taskId, String instanceId,
                                                  String nodeId, String approverId,
                                                  String approverName, TaskStatus status) {
        ApprovalTask task = new ApprovalTask();
        task.setTaskId(taskId);
        task.setInstanceId(instanceId);
        task.setNodeId(nodeId);
        task.setNodeName("审批节点");
        task.setFormId("form_test_001");
        task.setTemplateId("template_test");
        task.setApproverId(approverId);
        task.setApproverName(approverName);
        task.setSubmitterId("user_001");
        task.setSubmitterName("测试用户");
        task.setFormTitle("测试审批申请");
        task.setTaskStatus(status);
        task.setPriority(0);
        task.setAssignedTime(LocalDateTime.now().minusHours(24));
        task.setDueTime(LocalDateTime.now().plusHours(24));
        return task;
    }

    public static ApprovalTask buildOverdueApprovalTask() {
        String taskId = "task_" + generateUniqueId();
        ApprovalTask task = buildApprovalTask(taskId, "instance_overdue", "node_approval",
                "user_manager_01", "部门经理", TaskStatus.PENDING);
        task.setAssignedTime(LocalDateTime.now().minusHours(48));
        task.setDueTime(LocalDateTime.now().minusHours(24));
        return task;
    }

    public static List<ApprovalTask> buildMultipleApprovalTasks(int count, String instanceId, String nodeId) {
        List<ApprovalTask> tasks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ApprovalTask task = buildApprovalTask(
                    "task_" + i + "_" + generateUniqueId(),
                    instanceId,
                    nodeId,
                    "user_approver_" + i,
                    "审批人" + i,
                    TaskStatus.PENDING
            );
            tasks.add(task);
        }
        return tasks;
    }

    public static ApprovalRecord buildBasicApprovalRecord() {
        String approvalId = "approval_" + generateUniqueId();
        return buildApprovalRecord(approvalId, "instance_test", "node_manager",
                "user_manager_01", "部门经理", ApprovalResult.APPROVED);
    }

    public static ApprovalRecord buildApprovalRecord(String approvalId, String instanceId,
                                                     String nodeId, String approverId,
                                                     String approverName, ApprovalResult result) {
        ApprovalRecord record = new ApprovalRecord();
        record.setApprovalId(approvalId);
        record.setInstanceId(instanceId);
        record.setNodeId(nodeId);
        record.setNodeName("审批节点");
        record.setFormId("form_test_001");
        record.setTaskId("task_test_001");
        record.setApproverId(approverId);
        record.setApproverName(approverName);
        record.setApprovalResult(result);
        record.setApprovalComment("测试审批意见");
        record.setSubmitterId("user_001");
        record.setSubmitterName("测试用户");
        record.setActionType(result.name());
        record.setSortOrder(1);
        record.setApprovalTime(LocalDateTime.now());
        return record;
    }

    public static List<ApprovalRecord> buildApprovalHistory(String instanceId, int count) {
        List<ApprovalRecord> records = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ApprovalRecord record = buildApprovalRecord(
                    "approval_" + i + "_" + generateUniqueId(),
                    instanceId,
                    "node_" + i,
                    "user_approver_" + i,
                    "审批人" + i,
                    ApprovalResult.APPROVED
            );
            record.setSortOrder(i);
            record.setApprovalTime(LocalDateTime.now().minusMinutes(30 * i));
            records.add(record);
        }
        return records;
    }

    public static List<String> buildApproverList(int count) {
        List<String> approvers = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            approvers.add("user_approver_" + i);
        }
        return approvers;
    }

    public static ReminderConfig buildReminderConfig() {
        return ReminderConfig.builder()
                .enabled(true)
                .intervalHours(24)
                .maxReminders(3)
                .escalationEnabled(true)
                .escalationAfterHours(48)
                .escalationApprovers(Arrays.asList("user_director_01"))
                .build();
    }

    public static class ReminderConfig {
        private boolean enabled;
        private int intervalHours;
        private int maxReminders;
        private boolean escalationEnabled;
        private int escalationAfterHours;
        private List<String> escalationApprovers;

        public static ReminderConfigBuilder builder() {
            return new ReminderConfigBuilder();
        }

        public static class ReminderConfigBuilder {
            private ReminderConfig config = new ReminderConfig();

            public ReminderConfigBuilder enabled(boolean enabled) {
                config.enabled = enabled;
                return this;
            }

            public ReminderConfigBuilder intervalHours(int hours) {
                config.intervalHours = hours;
                return this;
            }

            public ReminderConfigBuilder maxReminders(int max) {
                config.maxReminders = max;
                return this;
            }

            public ReminderConfigBuilder escalationEnabled(boolean enabled) {
                config.escalationEnabled = enabled;
                return this;
            }

            public ReminderConfigBuilder escalationAfterHours(int hours) {
                config.escalationAfterHours = hours;
                return this;
            }

            public ReminderConfigBuilder escalationApprovers(List<String> approvers) {
                config.escalationApprovers = approvers;
                return this;
            }

            public ReminderConfig build() {
                return config;
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public int getIntervalHours() {
            return intervalHours;
        }

        public int getMaxReminders() {
            return maxReminders;
        }

        public boolean isEscalationEnabled() {
            return escalationEnabled;
        }

        public int getEscalationAfterHours() {
            return escalationAfterHours;
        }

        public List<String> getEscalationApprovers() {
            return escalationApprovers;
        }
    }
}
