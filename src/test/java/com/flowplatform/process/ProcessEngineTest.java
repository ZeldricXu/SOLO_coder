package com.flowplatform.process;

import com.alibaba.fastjson2.JSONObject;
import com.flowplatform.common.ProcessEngine;
import com.flowplatform.test.BaseUnitTest;
import com.flowplatform.test.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("流程引擎测试")
public class ProcessEngineTest extends BaseUnitTest {

    private final ProcessEngine engine = new ProcessEngine();

    @Test
    @DisplayName("简单流程测试 - 开始→审批→结束")
    public void testSimpleProcess() {
        JSONObject process = TestDataFactory.simpleProcessDefinition();

        Map<String, ProcessEngine.ApprovalAction> actions = new HashMap<>();
        actions.put("approve1", ProcessEngine.ApprovalAction.approve("同意"));

        ProcessEngine.ProcessExecutionResult result = engine.executeProcess(
                process, new HashMap<>(), actions);

        assertEquals("COMPLETED", result.status(), "流程状态应为已完成");
        assertTrue(result.executedNodes().contains("start"), "应执行开始节点");
        assertTrue(result.executedNodes().contains("approve1"), "应执行审批节点");
        assertTrue(result.executedNodes().contains("end"), "应执行结束节点");
        assertEquals("APPROVED", result.nodeResults().get("approve1"), "审批节点结果应为通过");
        assertTrue(engine.getErrors().isEmpty(), "不应有执行错误");
    }

    @Test
    @DisplayName("简单流程 - 待审批状态测试")
    public void testSimpleProcessPending() {
        JSONObject process = TestDataFactory.simpleProcessDefinition();

        Map<String, ProcessEngine.ApprovalAction> actions = new HashMap<>();

        ProcessEngine.ProcessExecutionResult result = engine.executeProcess(
                process, new HashMap<>(), actions);

        assertEquals("RUNNING", result.status(), "流程状态应为运行中");
        assertTrue(result.currentNodes().contains("approve1"), "当前节点应为审批节点");
        assertFalse(result.executedNodes().contains("end"), "不应执行到结束节点");
    }

    @Test
    @DisplayName("简单流程 - 拒绝测试")
    public void testSimpleProcessReject() {
        JSONObject process = TestDataFactory.simpleProcessDefinition();

        Map<String, ProcessEngine.ApprovalAction> actions = new HashMap<>();
        actions.put("approve1", ProcessEngine.ApprovalAction.reject("拒绝申请"));

        ProcessEngine.ProcessExecutionResult result = engine.executeProcess(
                process, new HashMap<>(), actions);

        assertEquals("REJECTED", result.status(), "流程状态应为已拒绝");
        assertEquals("REJECTED", result.nodeResults().get("approve1"), "审批节点结果应为拒绝");
        assertFalse(result.executedNodes().contains("end"), "拒绝后不应执行到结束节点");
    }

    @Test
    @DisplayName("条件分支 - 金额>1000走部门经理审批")
    public void testConditionHighAmount() {
        JSONObject process = TestDataFactory.conditionProcessDefinition();

        Map<String, Object> formData = new HashMap<>();
        formData.put("amount", 5000);

        Map<String, ProcessEngine.ApprovalAction> actions = new HashMap<>();
        actions.put("approve_mgr", ProcessEngine.ApprovalAction.approve("同意"));

        ProcessEngine.ProcessExecutionResult result = engine.executeProcess(
                process, formData, actions);

        assertEquals("COMPLETED", result.status(), "流程状态应为已完成");
        assertTrue(result.executedNodes().contains("approve_mgr"), "应执行部门经理审批节点");
        assertFalse(result.executedNodes().contains("approve_leader"), "不应执行直接上级审批节点");
    }

    @Test
    @DisplayName("条件分支 - 金额<=1000走直接上级审批")
    public void testConditionLowAmount() {
        JSONObject process = TestDataFactory.conditionProcessDefinition();

        Map<String, Object> formData = new HashMap<>();
        formData.put("amount", 500);

        Map<String, ProcessEngine.ApprovalAction> actions = new HashMap<>();
        actions.put("approve_leader", ProcessEngine.ApprovalAction.approve("同意"));

        ProcessEngine.ProcessExecutionResult result = engine.executeProcess(
                process, formData, actions);

        assertEquals("COMPLETED", result.status(), "流程状态应为已完成");
        assertTrue(result.executedNodes().contains("approve_leader"), "应执行直接上级审批节点");
        assertFalse(result.executedNodes().contains("approve_mgr"), "不应执行部门经理审批节点");
    }

    @Test
    @DisplayName("条件表达式计算测试")
    public void testConditionEvaluation() {
        Map<String, Object> formData = new HashMap<>();
        formData.put("amount", 1500);
        formData.put("days", 5);

        assertTrue(engine.evaluateCondition("${amount} > 1000", formData), "1500 > 1000 应为true");
        assertFalse(engine.evaluateCondition("${amount} <= 1000", formData), "1500 <= 1000 应为false");
        assertTrue(engine.evaluateCondition("${days} >= 3", formData), "5 >= 3 应为true");
        assertTrue(engine.evaluateCondition("${days} < 10", formData), "5 < 10 应为true");
    }

    @Test
    @DisplayName("并行会签 - 三人同意通过测试")
    public void testParallelSignAllApprove() {
        JSONObject process = TestDataFactory.parallelSignProcessDefinition();

        Map<String, ProcessEngine.ApprovalAction> actions = new HashMap<>();
        actions.put("parallel1_0", ProcessEngine.ApprovalAction.approve("同意"));
        actions.put("parallel1_1", ProcessEngine.ApprovalAction.approve("同意"));
        actions.put("parallel1_2", ProcessEngine.ApprovalAction.approve("同意"));

        ProcessEngine.ProcessExecutionResult result = engine.executeProcess(
                process, new HashMap<>(), actions);

        assertEquals("COMPLETED", result.status(), "流程状态应为已完成");
        assertEquals("APPROVED", result.nodeResults().get("parallel1"), "会签节点结果应为通过");
        assertEquals(3, result.nodeAssignees().get("parallel1").size(), "应有3个审批人");
    }

    @Test
    @DisplayName("并行会签 - 两人同意一人拒绝测试")
    public void testParallelSignWithReject() {
        JSONObject process = TestDataFactory.parallelSignProcessDefinition();

        Map<String, ProcessEngine.ApprovalAction> actions = new HashMap<>();
        actions.put("parallel1_0", ProcessEngine.ApprovalAction.approve("同意"));
        actions.put("parallel1_1", ProcessEngine.ApprovalAction.reject("拒绝"));
        actions.put("parallel1_2", ProcessEngine.ApprovalAction.approve("同意"));

        ProcessEngine.ProcessExecutionResult result = engine.executeProcess(
                process, new HashMap<>(), actions);

        assertEquals("REJECTED", result.status(), "流程状态应为已拒绝（需全部同意）");
        assertEquals("REJECTED", result.nodeResults().get("parallel1"), "会签节点结果应为拒绝");
    }

    @Test
    @DisplayName("并行会签 - 部分审批中状态测试")
    public void testParallelSignPartialApproved() {
        JSONObject process = TestDataFactory.parallelSignProcessDefinition();

        Map<String, ProcessEngine.ApprovalAction> actions = new HashMap<>();
        actions.put("parallel1_0", ProcessEngine.ApprovalAction.approve("同意"));

        ProcessEngine.ProcessExecutionResult result = engine.executeProcess(
                process, new HashMap<>(), actions);

        assertEquals("RUNNING", result.status(), "流程状态应为运行中");
        assertTrue(result.currentNodes().contains("parallel1"), "当前节点应为并行会签");
        assertFalse(result.nodeResults().containsKey("parallel1"), "会签节点不应有最终结果");
    }

    @Test
    @DisplayName("异常流程 - 死循环检测")
    public void testCycleDetection() {
        JSONObject process = TestDataFactory.cycleProcessDefinition();

        Map<String, ProcessEngine.ApprovalAction> actions = new HashMap<>();

        ProcessEngine.ProcessExecutionResult result = engine.executeProcess(
                process, new HashMap<>(), actions);

        assertEquals("ERROR", result.status(), "流程状态应为错误");
        assertTrue(engine.getErrors().stream().anyMatch(e -> e.contains("循环")),
                "应检测到循环引用错误");
    }

    @Test
    @DisplayName("审批人不存在检测")
    public void testNonExistentAssignee() {
        JSONObject process = TestDataFactory.simpleProcessDefinition();

        Set<Long> validUsers = Set.of(1L, 2L, 3L);
        process.getJSONArray("nodes").getJSONObject(1).put("assigneeId", 999L);

        boolean isValid = engine.validateAssigneesExist(process, validUsers);

        assertFalse(isValid, "应检测到不存在的审批人");
        assertTrue(engine.getErrors().stream().anyMatch(e -> e.contains("不存在")),
                "错误信息应指出审批人不存在");
    }

    @Test
    @DisplayName("审批人全部存在验证通过")
    public void testAllAssigneesExist() {
        JSONObject process = TestDataFactory.parallelSignProcessDefinition();

        Set<Long> validUsers = Set.of(101L, 102L, 103L);

        boolean isValid = engine.validateAssigneesExist(process, validUsers);

        assertTrue(isValid, "所有审批人都存在，验证应通过");
        assertTrue(engine.getErrors().isEmpty(), "不应有错误");
    }

    @Test
    @DisplayName("审批超时自动退回测试")
    public void testTimeoutAutoReturn() {
        JSONObject process = TestDataFactory.simpleProcessDefinition();
        process.getJSONArray("nodes").getJSONObject(1).put("timeoutDays", 3);
        process.getJSONArray("nodes").getJSONObject(1).put("timeoutAction", "RETURN");

        Map<String, ProcessEngine.ApprovalAction> actions = new HashMap<>();

        ProcessEngine.ProcessExecutionResult result = engine.executeProcess(
                process, new HashMap<>(), actions);

        assertEquals("RUNNING", result.status(), "流程应处于等待状态");
    }

    @Test
    @DisplayName("空流程定义处理")
    public void testEmptyProcessDefinition() {
        JSONObject process = new JSONObject();

        ProcessEngine.ProcessExecutionResult result = engine.executeProcess(
                process, new HashMap<>(), new HashMap<>());

        assertEquals("ERROR", result.status(), "空流程定义应为错误");
        assertTrue(engine.getErrors().size() > 0, "应有错误信息");
    }
}
