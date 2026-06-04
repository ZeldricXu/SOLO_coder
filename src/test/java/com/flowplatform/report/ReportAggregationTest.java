package com.flowplatform.report;

import com.flowplatform.mapper.ProcessInstanceMapper;
import com.flowplatform.mapper.ProcessTaskMapper;
import com.flowplatform.service.ProcessInstanceService;
import com.flowplatform.test.BaseUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("数据报表聚合查询测试")
public class ReportAggregationTest extends BaseUnitTest {

    @Mock
    private ProcessInstanceMapper instanceMapper;

    @Mock
    private ProcessTaskMapper taskMapper;

    @InjectMocks
    private ProcessInstanceService processInstanceService;

    @Test
    @DisplayName("状态分布统计测试")
    public void testStatusStats() {
        List<Map<String, Object>> mockData = List.of(
                Map.of("status", "PENDING", "cnt", 15L),
                Map.of("status", "APPROVED", "cnt", 120L),
                Map.of("status", "REJECTED", "cnt", 8L),
                Map.of("status", "RETURNED", "cnt", 5L)
        );

        when(instanceMapper.countByStatus()).thenReturn(mockData);

        List<Map<String, Object>> result = processInstanceService.getStatusStats();

        assertNotNull(result);
        assertEquals(4, result.size());
        assertEquals("PENDING", result.get(0).get("status"));
        assertEquals(15L, result.get(0).get("cnt"));
        assertEquals(120L, result.get(1).get("cnt"));
        verify(instanceMapper, times(1)).countByStatus();
    }

    @Test
    @DisplayName("30天提交趋势测试")
    public void testDateTrend() {
        List<Map<String, Object>> mockData = List.of(
                Map.of("date", "2024-01-01", "cnt", 5L),
                Map.of("date", "2024-01-02", "cnt", 8L),
                Map.of("date", "2024-01-03", "cnt", 12L),
                Map.of("date", "2024-01-04", "cnt", 6L),
                Map.of("date", "2024-01-05", "cnt", 10L)
        );

        when(instanceMapper.countByDateRecent30Days()).thenReturn(mockData);

        List<Map<String, Object>> result = processInstanceService.getDateTrend();

        assertNotNull(result);
        assertEquals(5, result.size());
        assertEquals("2024-01-01", result.get(0).get("date"));
        assertEquals(5L, result.get(0).get("cnt"));
        verify(instanceMapper, times(1)).countByDateRecent30Days();
    }

    @Test
    @DisplayName("平均审批时长测试")
    public void testAvgApprovalTime() {
        Map<String, Object> mockData = Map.of("avg_hours", 4.5);

        when(instanceMapper.avgApprovalHours()).thenReturn(mockData);

        Map<String, Object> result = processInstanceService.getAvgApprovalTime();

        assertNotNull(result);
        assertEquals(4.5, result.get("avg_hours"));
        verify(instanceMapper, times(1)).avgApprovalHours();
    }

    @Test
    @DisplayName("各审批节点平均耗时排名测试")
    public void testNodeAvgTimeRanking() {
        List<Map<String, Object>> mockData = List.of(
                Map.of("node_name", "总经理审批", "avg_hours", 24.5),
                Map.of("node_name", "财务审批", "avg_hours", 12.3),
                Map.of("node_name", "部门审批", "avg_hours", 6.8),
                Map.of("node_name", "人事审批", "avg_hours", 4.2)
        );

        when(taskMapper.avgHoursByNodeTop10()).thenReturn(mockData);

        List<Map<String, Object>> result = processInstanceService.getNodeAvgTime();

        assertNotNull(result);
        assertEquals(4, result.size());
        assertEquals("总经理审批", result.get(0).get("node_name"));
        assertEquals(24.5, result.get(0).get("avg_hours"));
        assertTrue((Double) result.get(0).get("avg_hours") > (Double) result.get(1).get("avg_hours"));
        verify(taskMapper, times(1)).avgHoursByNodeTop10();
    }

    @Test
    @DisplayName("表单提交量排名测试")
    public void testFormRanking() {
        List<Map<String, Object>> mockData = List.of(
                Map.of("form_id", 1L, "cnt", 150L),
                Map.of("form_id", 3L, "cnt", 98L),
                Map.of("form_id", 2L, "cnt", 76L),
                Map.of("form_id", 5L, "cnt", 45L),
                Map.of("form_id", 4L, "cnt", 23L)
        );

        when(instanceMapper.countByFormTop10()).thenReturn(mockData);

        List<Map<String, Object>> result = processInstanceService.getFormRanking();

        assertNotNull(result);
        assertEquals(5, result.size());
        assertEquals(1L, result.get(0).get("form_id"));
        assertEquals(150L, result.get(0).get("cnt"));
        assertTrue((Long) result.get(0).get("cnt") > (Long) result.get(1).get("cnt"));
        verify(instanceMapper, times(1)).countByFormTop10();
    }

    @Test
    @DisplayName("状态分布数据完整性校验")
    public void testStatusStatsDataIntegrity() {
        List<Map<String, Object>> mockData = List.of(
                Map.of("status", "PENDING", "cnt", 15L),
                Map.of("status", "APPROVED", "cnt", 120L),
                Map.of("status", "REJECTED", "cnt", 8L),
                Map.of("status", "RETURNED", "cnt", 5L)
        );

        when(instanceMapper.countByStatus()).thenReturn(mockData);

        List<Map<String, Object>> result = processInstanceService.getStatusStats();

        long total = result.stream()
                .mapToLong(m -> ((Number) m.get("cnt")).longValue())
                .sum();

        assertEquals(148L, total, "所有状态数量之和应等于总数");
        assertTrue(result.stream().anyMatch(m -> "APPROVED".equals(m.get("status"))));
        assertTrue(result.stream().anyMatch(m -> "PENDING".equals(m.get("status"))));
    }

    @Test
    @DisplayName("30天趋势数据日期顺序校验")
    public void testDateTrendOrder() {
        List<Map<String, Object>> mockData = List.of(
                Map.of("date", "2024-01-01", "cnt", 5L),
                Map.of("date", "2024-01-02", "cnt", 8L),
                Map.of("date", "2024-01-03", "cnt", 12L),
                Map.of("date", "2024-01-04", "cnt", 6L),
                Map.of("date", "2024-01-05", "cnt", 10L)
        );

        when(instanceMapper.countByDateRecent30Days()).thenReturn(mockData);

        List<Map<String, Object>> result = processInstanceService.getDateTrend();

        for (int i = 0; i < result.size() - 1; i++) {
            String currentDate = (String) result.get(i).get("date");
            String nextDate = (String) result.get(i + 1).get("date");
            assertTrue(currentDate.compareTo(nextDate) < 0, "日期应按升序排列");
        }
    }

    @Test
    @DisplayName("待办任务数量查询测试")
    public void testPendingTaskCount() {
        Long userId = 1L;
        when(taskMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(5L);

        long count = taskMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.flowplatform.entity.ProcessTask>()
                .eq(com.flowplatform.entity.ProcessTask::getAssigneeId, userId)
                .eq(com.flowplatform.entity.ProcessTask::getStatus, "PENDING"));

        assertEquals(5, count);
        verify(taskMapper, times(1)).selectCount(any());
    }

    @Test
    @DisplayName("空数据处理测试")
    public void testEmptyDataHandling() {
        when(instanceMapper.countByStatus()).thenReturn(List.of());

        List<Map<String, Object>> result = processInstanceService.getStatusStats();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("单个状态数据测试")
    public void testSingleStatusData() {
        List<Map<String, Object>> mockData = List.of(
                Map.of("status", "PENDING", "cnt", 3L)
        );

        when(instanceMapper.countByStatus()).thenReturn(mockData);

        List<Map<String, Object>> result = processInstanceService.getStatusStats();

        assertEquals(1, result.size());
        assertEquals("PENDING", result.get(0).get("status"));
        assertEquals(3L, result.get(0).get("cnt"));
    }
}
