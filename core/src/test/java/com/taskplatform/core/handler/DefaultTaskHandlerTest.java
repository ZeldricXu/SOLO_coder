package com.taskplatform.core.handler;

import com.taskplatform.common.enums.TaskPriority;
import com.taskplatform.core.TaskContext;
import com.taskplatform.persistence.entity.Task;
import com.taskplatform.test.builder.TaskBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("默认任务处理器测试")
class DefaultTaskHandlerTest {

    private final DefaultTaskHandler handler = new DefaultTaskHandler();

    @Test
    @DisplayName("类型匹配 - 应支持default和generic类型")
    void shouldSupportCorrectTaskTypes() {
        assertThat(handler.canHandle("default")).isTrue();
        assertThat(handler.canHandle("generic")).isTrue();
        assertThat(handler.canHandle("other")).isFalse();
        assertThat(handler.canHandle("")).isFalse();
        assertThat(handler.canHandle(null)).isFalse();
    }

    @Test
    @DisplayName("顺序优先级 - 应为最低优先级")
    void shouldHaveLowestOrder() {
        assertThat(handler.getOrder()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("正常执行 - 应返回正确的结果结构")
    void shouldExecuteSuccessfully() throws Exception {
        Task task = TaskBuilder.aTask()
                .withTaskId("handler-test-001")
                .withType("default")
                .withName("Test Task")
                .withPayload("{\"key\": \"value\"}")
                .build();
        TaskContext context = new TaskContext(task);

        Object result = handler.execute(context);

        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap).containsEntry("taskId", "handler-test-001");
        assertThat(resultMap).containsEntry("status", "completed");
        assertThat(resultMap).containsKey("processedAt");
        assertThat(resultMap).containsKey("inputSize");
    }

    @Test
    @DisplayName("空Payload - 应优雅处理")
    void shouldHandleNullPayload() throws Exception {
        Task task = TaskBuilder.aTask()
                .withTaskId("handler-test-002")
                .withType("default")
                .withPayload(null)
                .build();
        TaskContext context = new TaskContext(task);

        Object result = handler.execute(context);

        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap).containsEntry("inputSize", 0);
    }

    @Test
    @DisplayName("空JSON Payload - 应正确处理")
    void shouldHandleEmptyJsonPayload() throws Exception {
        Task task = TaskBuilder.aTask()
                .withTaskId("handler-test-003")
                .withType("default")
                .withPayload("{}")
                .build();
        TaskContext context = new TaskContext(task);

        Object result = handler.execute(context);

        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap).containsEntry("inputSize", 0);
    }

    @Test
    @DisplayName("无效JSON Payload - 应优雅降级")
    void shouldHandleInvalidJsonPayload() {
        Task task = TaskBuilder.aTask()
                .withTaskId("handler-test-004")
                .withType("default")
                .withPayload("not a valid json")
                .build();
        TaskContext context = new TaskContext(task);

        assertThatCode(() -> handler.execute(context))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("大Payload - 应正常处理")
    void shouldHandleLargePayload() throws Exception {
        StringBuilder largeJson = new StringBuilder("{");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) largeJson.append(",");
            largeJson.append("\"key").append(i).append("\": \"value").append(i).append("\"");
        }
        largeJson.append("}");

        Task task = TaskBuilder.aTask()
                .withTaskId("handler-test-005")
                .withType("default")
                .withPayload(largeJson.toString())
                .build();
        TaskContext context = new TaskContext(task);

        Object result = handler.execute(context);

        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(((Number) resultMap.get("inputSize")).intValue()).isEqualTo(1000);
    }

    @Test
    @DisplayName("不同优先级任务 - 执行结果一致")
    void shouldHandleAllPriorities() throws Exception {
        for (TaskPriority priority : TaskPriority.values()) {
            Task task = TaskBuilder.aTask()
                    .withTaskId("priority-test-" + priority)
                    .withType("default")
                    .withPriority(priority)
                    .build();
            TaskContext context = new TaskContext(task);

            Object result = handler.execute(context);

            assertThat(result).isInstanceOf(Map.class);
            Map<String, Object> resultMap = (Map<String, Object>) result;
            assertThat(resultMap).containsEntry("taskId", "priority-test-" + priority);
        }
    }
}
