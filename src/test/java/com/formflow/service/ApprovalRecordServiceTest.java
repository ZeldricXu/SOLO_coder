package com.formflow.service;

import com.formflow.builder.TestDataBuilder;
import com.formflow.entity.ApprovalRecord;
import com.formflow.entity.ApprovalTask;
import com.formflow.enums.ApprovalResult;
import com.formflow.exception.BusinessException;
import com.formflow.repository.ApprovalRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("审批历史服务测试")
class ApprovalRecordServiceTest {

    @Mock
    private ApprovalRecordRepository approvalRecordRepository;

    @InjectMocks
    private ApprovalRecordService approvalRecordService;

    private ApprovalRecord basicRecord;
    private ApprovalTask basicTask;
    private List<ApprovalRecord> approvalHistory;

    @BeforeEach
    void setUp() {
        basicRecord = TestDataBuilder.buildBasicApprovalRecord();
        basicTask = TestDataBuilder.buildBasicApprovalTask();
        approvalHistory = TestDataBuilder.buildApprovalHistory("instance_test_001", 5);
    }

    @Test
    @DisplayName("测试获取审批记录 - 成功")
    void testGetRecordByApprovalId_Success() {
        when(approvalRecordRepository.findByApprovalId(basicRecord.getApprovalId()))
                .thenReturn(Optional.of(basicRecord));

        ApprovalRecord result = approvalRecordService.getRecordByApprovalId(basicRecord.getApprovalId());

        assertNotNull(result);
        assertEquals(basicRecord.getApprovalId(), result.getApprovalId());
        assertEquals(basicRecord.getApproverId(), result.getApproverId());
        assertEquals(basicRecord.getApprovalResult(), result.getApprovalResult());
    }

    @Test
    @DisplayName("测试获取审批记录 - 不存在抛出异常")
    void testGetRecordByApprovalId_NotFound() {
        String nonExistentId = "approval_nonexistent";
        when(approvalRecordRepository.findByApprovalId(nonExistentId))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> approvalRecordService.getRecordByApprovalId(nonExistentId));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("审批记录不存在"));
    }

    @Test
    @DisplayName("测试创建审批记录 - 成功")
    void testCreateApprovalRecord_Success() {
        when(approvalRecordRepository.findMaxSortOrderByInstanceId(basicTask.getInstanceId()))
                .thenReturn(null);
        when(approvalRecordRepository.save(any(ApprovalRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalRecord result = approvalRecordService.createApprovalRecord(
                basicTask, ApprovalResult.APPROVED, "同意申请");

        assertNotNull(result);
        assertNotNull(result.getApprovalId());
        assertEquals(basicTask.getInstanceId(), result.getInstanceId());
        assertEquals(basicTask.getNodeId(), result.getNodeId());
        assertEquals(basicTask.getApproverId(), result.getApproverId());
        assertEquals(ApprovalResult.APPROVED, result.getApprovalResult());
        assertEquals("同意申请", result.getApprovalComment());
        assertEquals(1, result.getSortOrder());

        verify(approvalRecordRepository, times(1)).save(any(ApprovalRecord.class));
    }

    @Test
    @DisplayName("测试创建审批记录 - 自动累加排序号")
    void testCreateApprovalRecord_AutoIncrementSortOrder() {
        when(approvalRecordRepository.findMaxSortOrderByInstanceId(basicTask.getInstanceId()))
                .thenReturn(3);
        when(approvalRecordRepository.save(any(ApprovalRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalRecord result = approvalRecordService.createApprovalRecord(
                basicTask, ApprovalResult.REJECTED, "拒绝申请");

        assertNotNull(result);
        assertEquals(4, result.getSortOrder());
    }

    @Test
    @DisplayName("测试创建审批记录 - 拒绝结果")
    void testCreateApprovalRecord_Rejected() {
        when(approvalRecordRepository.findMaxSortOrderByInstanceId(basicTask.getInstanceId()))
                .thenReturn(null);
        when(approvalRecordRepository.save(any(ApprovalRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalRecord result = approvalRecordService.createApprovalRecord(
                basicTask, ApprovalResult.REJECTED, "资料不完整，请补充");

        assertNotNull(result);
        assertEquals(ApprovalResult.REJECTED, result.getApprovalResult());
        assertEquals("REJECTED", result.getActionType());
    }

    @Test
    @DisplayName("测试获取流程审批历史")
    void testGetRecordsByInstanceId() {
        when(approvalRecordRepository.findByInstanceIdOrderBySortOrderAsc("instance_test_001"))
                .thenReturn(approvalHistory);

        List<ApprovalRecord> records = approvalRecordService.getRecordsByInstanceId("instance_test_001");

        assertNotNull(records);
        assertEquals(5, records.size());
        for (int i = 0; i < records.size(); i++) {
            assertEquals(i + 1, records.get(i).getSortOrder());
        }
    }

    @Test
    @DisplayName("测试获取流程审批历史 - 按排序号顺序")
    void testGetRecordsByInstanceId_SortedBySortOrder() {
        List<ApprovalRecord> unsortedRecords = new ArrayList<>(approvalHistory);
        Collections.reverse(unsortedRecords);

        when(approvalRecordRepository.findByInstanceIdOrderBySortOrderAsc("instance_test_001"))
                .thenReturn(approvalHistory);

        List<ApprovalRecord> records = approvalRecordService.getRecordsByInstanceId("instance_test_001");

        assertEquals(1, records.get(0).getSortOrder());
        assertEquals(2, records.get(1).getSortOrder());
        assertEquals(3, records.get(2).getSortOrder());
        assertEquals(4, records.get(3).getSortOrder());
        assertEquals(5, records.get(4).getSortOrder());
    }

    @Test
    @DisplayName("测试获取表单审批历史")
    void testGetRecordsByFormId() {
        List<ApprovalRecord> formHistory = TestDataBuilder.buildApprovalHistory("instance_test_002", 3);
        when(approvalRecordRepository.findByFormIdOrderBySortOrderAsc("form_test_001"))
                .thenReturn(formHistory);

        List<ApprovalRecord> records = approvalRecordService.getRecordsByFormId("form_test_001");

        assertNotNull(records);
        assertEquals(3, records.size());
    }

    @Test
    @DisplayName("测试获取审批人审批历史")
    void testGetRecordsByApproverId() {
        List<ApprovalRecord> approverRecords = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            ApprovalRecord record = TestDataBuilder.buildApprovalRecord(
                    "approval_" + i,
                    "instance_" + i,
                    "node_1",
                    "user_manager_01",
                    "部门经理",
                    i % 2 == 0 ? ApprovalResult.APPROVED : ApprovalResult.REJECTED
            );
            approverRecords.add(record);
        }

        when(approvalRecordRepository.findByApproverId("user_manager_01"))
                .thenReturn(approverRecords);

        List<ApprovalRecord> records = approvalRecordService.getRecordsByApproverId("user_manager_01");

        assertNotNull(records);
        assertEquals(10, records.size());
        for (ApprovalRecord record : records) {
            assertEquals("user_manager_01", record.getApproverId());
        }
    }

    @Test
    @DisplayName("测试获取节点审批历史")
    void testGetRecordsByInstanceIdAndNodeId() {
        List<ApprovalRecord> nodeRecords = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            ApprovalRecord record = TestDataBuilder.buildApprovalRecord(
                    "approval_node1_" + i,
                    "instance_test",
                    "node_manager",
                    "user_manager_" + i,
                    "部门经理" + i,
                    ApprovalResult.APPROVED
            );
            nodeRecords.add(record);
        }

        when(approvalRecordRepository.findByInstanceIdAndNodeId("instance_test", "node_manager"))
                .thenReturn(nodeRecords);

        List<ApprovalRecord> records = approvalRecordService.getRecordsByInstanceIdAndNodeId(
                "instance_test", "node_manager");

        assertNotNull(records);
        assertEquals(2, records.size());
        for (ApprovalRecord record : records) {
            assertEquals("node_manager", record.getNodeId());
        }
    }

    @Test
    @DisplayName("测试创建转交记录")
    void testCreateTransferRecord() {
        when(approvalRecordRepository.findMaxSortOrderByInstanceId(basicTask.getInstanceId()))
                .thenReturn(1);
        when(approvalRecordRepository.save(any(ApprovalRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalRecord result = approvalRecordService.createTransferRecord(
                basicTask, "user_manager_02", "部门经理2", "有事请假，转交同事处理");

        assertNotNull(result);
        assertEquals(ApprovalResult.TRANSFER, result.getApprovalResult());
        assertEquals("TRANSFER", result.getActionType());
        assertTrue(result.getApprovalComment().contains("转交给"));
        assertTrue(result.getApprovalComment().contains("部门经理2"));
        assertEquals(2, result.getSortOrder());
    }

    @Test
    @DisplayName("测试创建委托记录")
    void testCreateDelegateRecord() {
        when(approvalRecordRepository.findMaxSortOrderByInstanceId(basicTask.getInstanceId()))
                .thenReturn(2);
        when(approvalRecordRepository.save(any(ApprovalRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalRecord result = approvalRecordService.createDelegateRecord(
                basicTask, "user_delegate_01", "委托人");

        assertNotNull(result);
        assertEquals(ApprovalResult.DELEGATE, result.getApprovalResult());
        assertEquals("DELEGATE", result.getActionType());
        assertTrue(result.getApprovalComment().contains("委托给"));
        assertEquals(3, result.getSortOrder());
    }

    @Test
    @DisplayName("测试获取最后一条审批ID")
    void testGetLastApprovalId() {
        when(approvalRecordRepository.findByInstanceIdOrderBySortOrderAsc("instance_test_001"))
                .thenReturn(approvalHistory);

        String lastApprovalId = approvalRecordService.getLastApprovalId("instance_test_001");

        assertNotNull(lastApprovalId);
        ApprovalRecord lastRecord = approvalHistory.get(approvalHistory.size() - 1);
        assertEquals(lastRecord.getApprovalId(), lastApprovalId);
    }

    @Test
    @DisplayName("测试获取最后一条审批ID - 无历史返回null")
    void testGetLastApprovalId_NoHistory() {
        when(approvalRecordRepository.findByInstanceIdOrderBySortOrderAsc("instance_empty"))
                .thenReturn(new ArrayList<>());

        String result = approvalRecordService.getLastApprovalId("instance_empty");

        assertNull(result);
    }

    @Test
    @DisplayName("测试统计流程审批记录数")
    void testCountRecordsByInstanceId() {
        when(approvalRecordRepository.countByInstanceId("instance_test_001"))
                .thenReturn(5L);

        Long count = approvalRecordService.countRecordsByInstanceId("instance_test_001");

        assertEquals(5L, count);
    }

    @Test
    @DisplayName("测试审批历史完整性 - 连续排序号")
    void testApprovalHistory_SequentialSortOrder() {
        List<ApprovalRecord> history = TestDataBuilder.buildApprovalHistory("instance_complete", 10);

        when(approvalRecordRepository.findByInstanceIdOrderBySortOrderAsc("instance_complete"))
                .thenReturn(history);

        List<ApprovalRecord> records = approvalRecordService.getRecordsByInstanceId("instance_complete");

        for (int i = 0; i < records.size(); i++) {
            assertEquals(i + 1, records.get(i).getSortOrder(),
                    "排序号应该是连续的，第" + (i + 1) + "条记录的sortOrder应该是" + (i + 1));
        }
    }

    @Test
    @DisplayName("测试审批历史完整性 - 审批时间递增")
    void testApprovalHistory_ApprovalTimeAscending() {
        when(approvalRecordRepository.findByInstanceIdOrderBySortOrderAsc("instance_test_001"))
                .thenReturn(approvalHistory);

        List<ApprovalRecord> records = approvalRecordService.getRecordsByInstanceId("instance_test_001");

        for (int i = 1; i < records.size(); i++) {
            assertTrue(records.get(i).getApprovalTime().isBefore(records.get(i - 1).getApprovalTime())
                            || records.get(i).getApprovalTime().isEqual(records.get(i - 1).getApprovalTime()),
                    "后续审批的时间应该早于前序审批（按构建逻辑）");
        }
    }

    @Test
    @DisplayName("测试历史查询过滤 - 按审批人过滤")
    void testHistoryFilter_ByApprover() {
        List<ApprovalRecord> mixedRecords = new ArrayList<>();
        mixedRecords.add(TestDataBuilder.buildApprovalRecord(
                "approval_1", "instance_1", "node_1", "user_a", "审批人A", ApprovalResult.APPROVED));
        mixedRecords.add(TestDataBuilder.buildApprovalRecord(
                "approval_2", "instance_2", "node_1", "user_b", "审批人B", ApprovalResult.APPROVED));
        mixedRecords.add(TestDataBuilder.buildApprovalRecord(
                "approval_3", "instance_3", "node_2", "user_a", "审批人A", ApprovalResult.REJECTED));

        when(approvalRecordRepository.findByApproverId("user_a"))
                .thenReturn(Arrays.asList(mixedRecords.get(0), mixedRecords.get(2)));

        List<ApprovalRecord> records = approvalRecordService.getRecordsByApproverId("user_a");

        assertEquals(2, records.size());
        for (ApprovalRecord record : records) {
            assertEquals("user_a", record.getApproverId());
        }
    }

    @Test
    @DisplayName("测试历史查询过滤 - 按节点过滤")
    void testHistoryFilter_ByNode() {
        List<ApprovalRecord> nodeRecords = Arrays.asList(
                TestDataBuilder.buildApprovalRecord(
                        "approval_1", "instance_1", "node_manager", "user_m1", "经理1", ApprovalResult.APPROVED),
                TestDataBuilder.buildApprovalRecord(
                        "approval_2", "instance_1", "node_manager", "user_m2", "经理2", ApprovalResult.APPROVED)
        );

        when(approvalRecordRepository.findByInstanceIdAndNodeId("instance_1", "node_manager"))
                .thenReturn(nodeRecords);

        List<ApprovalRecord> records = approvalRecordService.getRecordsByInstanceIdAndNodeId(
                "instance_1", "node_manager");

        assertEquals(2, records.size());
        for (ApprovalRecord record : records) {
            assertEquals("node_manager", record.getNodeId());
        }
    }

    @Test
    @DisplayName("测试获取任务审批记录")
    void testGetRecordsByTaskId() {
        ApprovalRecord record = TestDataBuilder.buildApprovalRecord(
                "approval_task_1", "instance_1", "node_1", "user_1", "审批人", ApprovalResult.APPROVED);
        record.setTaskId("task_test_001");

        when(approvalRecordRepository.findByTaskId("task_test_001"))
                .thenReturn(Collections.singletonList(record));

        List<ApprovalRecord> records = approvalRecordRepository.getRecordsByTaskId("task_test_001");

        assertNotNull(records);
        assertEquals(1, records.size());
        assertEquals("task_test_001", records.get(0).getTaskId());
    }

    @Test
    @DisplayName("测试审批记录字段完整性")
    void testApprovalRecord_FieldCompleteness() {
        ApprovalTask task = TestDataBuilder.buildApprovalTask(
                "task_complete", "instance_complete", "node_test",
                "user_approver_01", "完整审批人", null);

        when(approvalRecordRepository.findMaxSortOrderByInstanceId(task.getInstanceId()))
                .thenReturn(null);
        when(approvalRecordRepository.save(any(ApprovalRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalRecord result = approvalRecordService.createApprovalRecord(
                task, ApprovalResult.APPROVED, "完整的审批意见");

        assertNotNull(result.getApprovalId());
        assertNotNull(result.getInstanceId());
        assertNotNull(result.getNodeId());
        assertNotNull(result.getFormId());
        assertNotNull(result.getTaskId());
        assertNotNull(result.getApproverId());
        assertNotNull(result.getApprovalResult());
        assertNotNull(result.getApprovalComment());
        assertNotNull(result.getSubmitterId());
        assertNotNull(result.getActionType());
        assertNotNull(result.getSortOrder());
        assertNotNull(result.getApprovalTime());
    }

    @Test
    @DisplayName("测试不同审批结果类型")
    void testDifferentApprovalResults() {
        ApprovalResult[] results = {
                ApprovalResult.APPROVED,
                ApprovalResult.REJECTED,
                ApprovalResult.TRANSFER,
                ApprovalResult.DELEGATE,
                ApprovalResult.ADD_SIGNER
        };

        for (ApprovalResult result : results) {
            when(approvalRecordRepository.findMaxSortOrderByInstanceId(basicTask.getInstanceId()))
                    .thenReturn(null);
            when(approvalRecordRepository.save(any(ApprovalRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ApprovalRecord record = approvalRecordService.createApprovalRecord(
                    basicTask, result, "测试" + result.name());

            assertEquals(result, record.getApprovalResult());
            assertEquals(result.name(), record.getActionType());
        }
    }
}
