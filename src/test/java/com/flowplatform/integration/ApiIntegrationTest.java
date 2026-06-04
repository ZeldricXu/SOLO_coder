package com.flowplatform.integration;

import com.alibaba.fastjson2.JSONObject;
import com.flowplatform.entity.FormDefinition;
import com.flowplatform.service.FormDefinitionService;
import com.flowplatform.test.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("API接口集成测试")
public class ApiIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FormDefinitionService formDefinitionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @Order(1)
    @DisplayName("保存表单API测试")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testSaveFormApi() throws Exception {
        JSONObject formData = new JSONObject();
        formData.put("formKey", "test_form_api_" + System.currentTimeMillis());
        formData.put("formName", "API测试表单");
        formData.put("formDesc", "这是一个API测试表单");
        formData.put("category", "通用");
        formData.put("formSchema", "{\"fields\":[{\"type\":\"textInput\",\"key\":\"name\",\"label\":\"姓名\",\"required\":true}]}");

        long startTime = System.currentTimeMillis();
        mockMvc.perform(post("/form/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(formData.toJSONString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "API响应时间应为" + responseTime + "ms < 1000ms");
    }

    @Test
    @Order(2)
    @DisplayName("表单列表API测试")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testFormListApi() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/form"))
                .andExpect(status().isOk())
                .andExpect(view().name("form/list"));

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "页面响应时间应为" + responseTime + "ms < 1000ms");
    }

    @Test
    @Order(3)
    @DisplayName("报表数据API - 状态分布")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testStatusStatsApi() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/report/api/status-stats"))
                .andExpect(status().isOk());

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "状态分布API响应时间应为" + responseTime + "ms < 1000ms");
    }

    @Test
    @Order(4)
    @DisplayName("报表数据API - 日期趋势")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testDateTrendApi() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/report/api/date-trend"))
                .andExpect(status().isOk());

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "日期趋势API响应时间应为" + responseTime + "ms < 1000ms");
    }

    @Test
    @Order(5)
    @DisplayName("报表数据API - 节点耗时排名")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testNodeTimeApi() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/report/api/node-time"))
                .andExpect(status().isOk());

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "节点耗时API响应时间应为" + responseTime + "ms < 1000ms");
    }

    @Test
    @Order(6)
    @DisplayName("报表数据API - 表单排名")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testFormRankingApi() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/report/api/form-ranking"))
                .andExpect(status().isOk());

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "表单排名API响应时间应为" + responseTime + "ms < 1000ms");
    }

    @Test
    @Order(7)
    @DisplayName("未读消息数量API")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testUnreadCountApi() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/notification/api/unread-count"))
                .andExpect(status().isOk());

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 500, "未读消息API响应时间应为" + responseTime + "ms < 500ms");
    }

    @Test
    @Order(8)
    @DisplayName("常用语列表API")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testQuickCommentsApi() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/api/quick-comments"))
                .andExpect(status().isOk());

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 500, "常用语API响应时间应为" + responseTime + "ms < 500ms");
    }

    @Test
    @Order(9)
    @DisplayName("审批中心待办页面")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testPendingTasksPage() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/approval/pending"))
                .andExpect(status().isOk())
                .andExpect(view().name("approval/pending"));

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "待办页面响应时间应为" + responseTime + "ms < 1000ms");
    }

    @Test
    @Order(10)
    @DisplayName("审批中心已办页面")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testCompletedTasksPage() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/approval/completed"))
                .andExpect(status().isOk())
                .andExpect(view().name("approval/completed"));

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "已办页面响应时间应为" + responseTime + "ms < 1000ms");
    }

    @Test
    @Order(11)
    @DisplayName("权限管理用户页面")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testPermissionUsersPage() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/permission/users"))
                .andExpect(status().isOk())
                .andExpect(view().name("permission/users"));

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "用户管理响应时间应为" + responseTime + "ms < 1000ms");
    }

    @Test
    @Order(12)
    @DisplayName("权限管理角色页面")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testPermissionRolesPage() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/permission/roles"))
                .andExpect(status().isOk())
                .andExpect(view().name("permission/roles"));

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "角色管理响应时间应为" + responseTime + "ms < 1000ms");
    }

    @Test
    @Order(13)
    @DisplayName("权限管理部门页面")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testPermissionDeptsPage() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/permission/departments"))
                .andExpect(status().isOk())
                .andExpect(view().name("permission/departments"));

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "部门管理响应时间应为" + responseTime + "ms < 1000ms");
    }

    @Test
    @Order(14)
    @DisplayName("消息中心页面")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testNotificationListPage() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/notification"))
                .andExpect(status().isOk())
                .andExpect(view().name("notification/list"));

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "消息中心响应时间应为" + responseTime + "ms < 1000ms");
    }

    @Test
    @Order(15)
    @DisplayName("通知偏好设置页面")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    void testNotificationPreferencesPage() throws Exception {
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/notification/preferences"))
                .andExpect(status().isOk())
                .andExpect(view().name("notification/preferences"));

        long responseTime = System.currentTimeMillis() - startTime;
        assertTrue(responseTime < 1000, "通知偏好响应时间应为" + responseTime + "ms < 1000ms");
    }
}
