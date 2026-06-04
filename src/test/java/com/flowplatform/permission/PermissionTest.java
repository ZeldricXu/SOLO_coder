package com.flowplatform.permission;

import com.flowplatform.service.SysUserService;
import com.flowplatform.test.BaseUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("权限控制测试")
public class PermissionTest extends BaseUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SysUserService sysUserService;

    @Test
    @DisplayName("未登录用户访问登录页 - 允许")
    public void testAnonymousAccessLogin() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未登录用户访问Dashboard - 重定向到登录页")
    public void testAnonymousAccessDashboard() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("管理员登录访问Dashboard - 200OK")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    public void testAdminAccessDashboard() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/index"));
    }

    @Test
    @DisplayName("管理员访问表单管理 - 200OK")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    public void testAdminAccessForm() throws Exception {
        mockMvc.perform(get("/form"))
                .andExpect(status().isOk())
                .andExpect(view().name("form/list"));
    }

    @Test
    @DisplayName("管理员访问流程管理 - 200OK")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    public void testAdminAccessProcess() throws Exception {
        mockMvc.perform(get("/process"))
                .andExpect(status().isOk())
                .andExpect(view().name("process/list"));
    }

    @Test
    @DisplayName("管理员访问审批中心 - 200OK")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    public void testAdminAccessApproval() throws Exception {
        mockMvc.perform(get("/approval"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/approval/pending"));
    }

    @Test
    @DisplayName("管理员访问数据报表 - 200OK")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    public void testAdminAccessReport() throws Exception {
        mockMvc.perform(get("/report"))
                .andExpect(status().isOk())
                .andExpect(view().name("report/index"));
    }

    @Test
    @DisplayName("管理员访问权限管理 - 200OK")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    public void testAdminAccessPermission() throws Exception {
        mockMvc.perform(get("/permission"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/permission/users"));
    }

    @Test
    @DisplayName("管理员访问消息中心 - 200OK")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    public void testAdminAccessNotification() throws Exception {
        mockMvc.perform(get("/notification"))
                .andExpect(status().isOk())
                .andExpect(view().name("notification/list"));
    }

    @Test
    @DisplayName("普通员工访问审批中心 - 200OK")
    @WithMockUser(username = "employee1", roles = {"EMPLOYEE"})
    public void testEmployeeAccessApproval() throws Exception {
        mockMvc.perform(get("/approval/pending"))
                .andExpect(status().isOk())
                .andExpect(view().name("approval/pending"));
    }

    @Test
    @DisplayName("普通员工访问表单管理 - 200OK(可查看)")
    @WithMockUser(username = "employee1", roles = {"EMPLOYEE"})
    public void testEmployeeAccessFormList() throws Exception {
        mockMvc.perform(get("/form"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("表单设计师访问表单创建页 - 200OK")
    @WithMockUser(username = "designer1", roles = {"FORM_DESIGNER"})
    public void testFormDesignerAccessCreate() throws Exception {
        mockMvc.perform(get("/form/create"))
                .andExpect(status().isOk())
                .andExpect(view().name("form/designer"));
    }

    @Test
    @DisplayName("流程设计师访问流程创建页 - 200OK")
    @WithMockUser(username = "designer2", roles = {"PROCESS_DESIGNER"})
    public void testProcessDesignerAccessCreate() throws Exception {
        mockMvc.perform(get("/process/create"))
                .andExpect(status().isOk())
                .andExpect(view().name("process/designer"));
    }

    @Test
    @DisplayName("部门经理访问数据报表 - 200OK")
    @WithMockUser(username = "manager1", roles = {"DEPT_MANAGER"})
    public void testDeptManagerAccessReport() throws Exception {
        mockMvc.perform(get("/report"))
                .andExpect(status().isOk())
                .andExpect(view().name("report/index"));
    }

    @Test
    @DisplayName("已登录用户访问静态资源 - 允许")
    @WithMockUser(username = "user1")
    public void testAuthenticatedAccessStatic() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未读消息数量API - 已登录用户可访问")
    @WithMockUser(username = "user1")
    public void testAuthenticatedAccessUnreadCount() throws Exception {
        mockMvc.perform(get("/notification/api/unread-count"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("报表数据API - 管理员可访问")
    @WithMockUser(username = "admin", roles = {"SUPER_ADMIN"})
    public void testAdminAccessReportApi() throws Exception {
        mockMvc.perform(get("/report/api/status-stats"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("常用语API - 已登录用户可访问")
    @WithMockUser(username = "user1")
    public void testAuthenticatedAccessQuickComments() throws Exception {
        mockMvc.perform(get("/api/quick-comments"))
                .andExpect(status().isOk());
    }
}
