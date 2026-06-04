package com.flowplatform.integration;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.*;
import com.flowplatform.test.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("端到端集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EndToEndTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    private WebClient webClient;

    @BeforeEach
    void setUp() {
        webClient = new WebClient();
        webClient.getOptions().setJavaScriptEnabled(true);
        webClient.getOptions().setCssEnabled(false);
        webClient.getOptions().setThrowExceptionOnScriptError(false);
        webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
        webClient.setAjaxController(new com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController());
    }

    @AfterEach
    void tearDown() {
        if (webClient != null) {
            webClient.close();
        }
    }

    protected String getBaseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    @Order(1)
    @DisplayName("登录页面响应时间测试 - 应在1秒内返回")
    void testLoginPageResponseTime() throws Exception {
        long startTime = System.currentTimeMillis();

        HtmlPage page = webClient.getPage(getBaseUrl() + "/login");

        long responseTime = System.currentTimeMillis() - startTime;

        assertEquals(200, page.getWebResponse().getStatusCode());
        assertTrue(responseTime < 1000, "登录页面响应时间应为" + responseTime + "ms < 1000ms");
        assertTrue(page.asNormalizedText().contains("登录"), "页面应包含登录标题");
    }

    @Test
    @Order(2)
    @DisplayName("登录功能测试 - admin/admin123")
    void testLoginWithValidCredentials() throws Exception {
        HtmlPage loginPage = webClient.getPage(getBaseUrl() + "/login");

        HtmlTextInput usernameInput = loginPage.getElementByName("username");
        HtmlPasswordInput passwordInput = loginPage.getElementByName("password");
        HtmlButton loginButton = loginPage.getFirstByXPath("//button[@type='submit']");

        usernameInput.setValue("admin");
        passwordInput.setValue("admin123");

        long startTime = System.currentTimeMillis();
        HtmlPage dashboardPage = loginButton.click();

        webClient.waitForBackgroundJavaScript(2000);

        long responseTime = System.currentTimeMillis() - startTime;

        assertTrue(responseTime < 1000, "登录响应时间应为" + responseTime + "ms < 1000ms");
        assertTrue(dashboardPage.getUrl().toString().contains("dashboard") ||
                        dashboardPage.asNormalizedText().contains("工作台"),
                "登录后应跳转到工作台");
    }

    @Test
    @Order(3)
    @DisplayName("工作台页面性能测试 - 应在1秒内加载")
    void testDashboardPerformance() throws Exception {
        loginAsAdmin();

        long startTime = System.currentTimeMillis();
        HtmlPage dashboardPage = webClient.getPage(getBaseUrl() + "/dashboard");

        long loadTime = System.currentTimeMillis() - startTime;

        assertEquals(200, dashboardPage.getWebResponse().getStatusCode());
        assertTrue(loadTime < 1000, "工作台加载时间应为" + loadTime + "ms < 1000ms");
        assertTrue(dashboardPage.asNormalizedText().contains("工作台"), "页面应显示工作台");
    }

    @Test
    @Order(4)
    @DisplayName("表单管理页面加载测试")
    void testFormListPage() throws Exception {
        loginAsAdmin();

        long startTime = System.currentTimeMillis();
        HtmlPage formListPage = webClient.getPage(getBaseUrl() + "/form");

        long loadTime = System.currentTimeMillis() - startTime;

        assertEquals(200, formListPage.getWebResponse().getStatusCode());
        assertTrue(loadTime < 1000, "表单列表加载时间应为" + loadTime + "ms < 1000ms");
        assertTrue(formListPage.asNormalizedText().contains("表单管理"), "页面应显示表单管理");
    }

    @Test
    @Order(5)
    @DisplayName("表单设计器页面加载测试")
    void testFormDesignerPage() throws Exception {
        loginAsAdmin();

        long startTime = System.currentTimeMillis();
        HtmlPage designerPage = webClient.getPage(getBaseUrl() + "/form/create");

        long loadTime = System.currentTimeMillis() - startTime;

        assertEquals(200, designerPage.getWebResponse().getStatusCode());
        assertTrue(loadTime < 1000, "表单设计器加载时间应为" + loadTime + "ms < 1000ms");
        assertTrue(designerPage.asNormalizedText().contains("字段组件"), "页面应显示字段组件面板");
    }

    @Test
    @Order(6)
    @DisplayName("流程管理页面加载测试")
    void testProcessListPage() throws Exception {
        loginAsAdmin();

        long startTime = System.currentTimeMillis();
        HtmlPage processListPage = webClient.getPage(getBaseUrl() + "/process");

        long loadTime = System.currentTimeMillis() - startTime;

        assertEquals(200, processListPage.getWebResponse().getStatusCode());
        assertTrue(loadTime < 1000, "流程列表加载时间应为" + loadTime + "ms < 1000ms");
        assertTrue(processListPage.asNormalizedText().contains("流程管理"), "页面应显示流程管理");
    }

    @Test
    @Order(7)
    @DisplayName("流程设计器页面加载测试")
    void testProcessDesignerPage() throws Exception {
        loginAsAdmin();

        long startTime = System.currentTimeMillis();
        HtmlPage designerPage = webClient.getPage(getBaseUrl() + "/process/create");

        long loadTime = System.currentTimeMillis() - startTime;

        assertEquals(200, designerPage.getWebResponse().getStatusCode());
        assertTrue(loadTime < 1000, "流程设计器加载时间应为" + loadTime + "ms < 1000ms");
        assertTrue(designerPage.asNormalizedText().contains("流程节点"), "页面应显示流程节点面板");
    }

    @Test
    @Order(8)
    @DisplayName("审批中心页面加载测试")
    void testApprovalCenterPage() throws Exception {
        loginAsAdmin();

        long startTime = System.currentTimeMillis();
        HtmlPage approvalPage = webClient.getPage(getBaseUrl() + "/approval/pending");

        long loadTime = System.currentTimeMillis() - startTime;

        assertEquals(200, approvalPage.getWebResponse().getStatusCode());
        assertTrue(loadTime < 1000, "审批中心加载时间应为" + loadTime + "ms < 1000ms");
        assertTrue(approvalPage.asNormalizedText().contains("待办事项"), "页面应显示待办事项");
    }

    @Test
    @Order(9)
    @DisplayName("数据报表页面加载测试")
    void testReportPage() throws Exception {
        loginAsAdmin();

        long startTime = System.currentTimeMillis();
        HtmlPage reportPage = webClient.getPage(getBaseUrl() + "/report");

        long loadTime = System.currentTimeMillis() - startTime;

        assertEquals(200, reportPage.getWebResponse().getStatusCode());
        assertTrue(loadTime < 1000, "数据报表加载时间应为" + loadTime + "ms < 1000ms");
        assertTrue(reportPage.asNormalizedText().contains("数据报表"), "页面应显示数据报表");
    }

    @Test
    @Order(10)
    @DisplayName("权限管理页面加载测试")
    void testPermissionPage() throws Exception {
        loginAsAdmin();

        long startTime = System.currentTimeMillis();
        HtmlPage permissionPage = webClient.getPage(getBaseUrl() + "/permission/users");

        long loadTime = System.currentTimeMillis() - startTime;

        assertEquals(200, permissionPage.getWebResponse().getStatusCode());
        assertTrue(loadTime < 1000, "权限管理加载时间应为" + loadTime + "ms < 1000ms");
        assertTrue(permissionPage.asNormalizedText().contains("用户管理"), "页面应显示用户管理");
    }

    @Test
    @Order(11)
    @DisplayName("消息中心页面加载测试")
    void testNotificationPage() throws Exception {
        loginAsAdmin();

        long startTime = System.currentTimeMillis();
        HtmlPage notificationPage = webClient.getPage(getBaseUrl() + "/notification");

        long loadTime = System.currentTimeMillis() - startTime;

        assertEquals(200, notificationPage.getWebResponse().getStatusCode());
        assertTrue(loadTime < 1000, "消息中心加载时间应为" + loadTime + "ms < 1000ms");
        assertTrue(notificationPage.asNormalizedText().contains("消息中心"), "页面应显示消息中心");
    }

    @Test
    @Order(12)
    @DisplayName("侧边栏导航测试")
    void testSidebarNavigation() throws Exception {
        loginAsAdmin();

        HtmlPage dashboardPage = webClient.getPage(getBaseUrl() + "/dashboard");

        List<?> navItems = dashboardPage.getByXPath("//div[@class='sidebar']//a[contains(@class, 'nav-item')]");

        assertTrue(navItems.size() >= 7, "侧边栏应包含至少7个导航项");
    }

    @Test
    @Order(13)
    @DisplayName("静态资源加载测试 - CSS")
    void testStaticCssLoading() throws Exception {
        long startTime = System.currentTimeMillis();
        com.gargoylesoftware.htmlunit.WebResponse response = webClient.getPage(getBaseUrl() + "/css/app.css").getWebResponse();

        long loadTime = System.currentTimeMillis() - startTime;

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getContentAsString().length() > 0, "CSS文件内容不应为空");
        assertTrue(loadTime < 500, "CSS加载时间应为" + loadTime + "ms < 500ms");
    }

    @Test
    @Order(14)
    @DisplayName("静态资源加载测试 - JavaScript")
    void testStaticJsLoading() throws Exception {
        long startTime = System.currentTimeMillis();
        com.gargoylesoftware.htmlunit.WebResponse response = webClient.getPage(getBaseUrl() + "/js/app.js").getWebResponse();

        long loadTime = System.currentTimeMillis() - startTime;

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getContentAsString().length() > 0, "JS文件内容不应为空");
        assertTrue(loadTime < 500, "JS加载时间应为" + loadTime + "ms < 500ms");
    }

    @Test
    @Order(15)
    @DisplayName("完整流程测试 - 登录→查看表单→查看审批→查看报表")
    void testFullWorkflow() throws Exception {
        long totalStartTime = System.currentTimeMillis();

        loginAsAdmin();

        long step1Start = System.currentTimeMillis();
        HtmlPage formPage = webClient.getPage(getBaseUrl() + "/form");
        assertTrue(formPage.asNormalizedText().contains("表单管理"));
        System.out.println("查看表单耗时: " + (System.currentTimeMillis() - step1Start) + "ms");

        long step2Start = System.currentTimeMillis();
        HtmlPage approvalPage = webClient.getPage(getBaseUrl() + "/approval/pending");
        assertTrue(approvalPage.asNormalizedText().contains("待办事项"));
        System.out.println("查看审批耗时: " + (System.currentTimeMillis() - step2Start) + "ms");

        long step3Start = System.currentTimeMillis();
        HtmlPage reportPage = webClient.getPage(getBaseUrl() + "/report");
        assertTrue(reportPage.asNormalizedText().contains("数据报表"));
        System.out.println("查看报表耗时: " + (System.currentTimeMillis() - step3Start) + "ms");

        long totalTime = System.currentTimeMillis() - totalStartTime;
        System.out.println("完整流程总耗时: " + totalTime + "ms");

        assertTrue(totalTime < 3000, "完整流程总耗时应为" + totalTime + "ms < 3000ms");
    }

    @Test
    @Order(16)
    @DisplayName("未登录访问保护测试 - 未登录访问应重定向到登录页")
    void testProtectedRouteWithoutLogin() throws Exception {
        webClient.getCookieManager().clearCookies();

        HtmlPage page = webClient.getPage(getBaseUrl() + "/dashboard");

        assertTrue(page.getUrl().toString().contains("login"),
                "未登录应重定向到登录页，当前URL: " + page.getUrl());
    }

    private void loginAsAdmin() throws Exception {
        HtmlPage loginPage = webClient.getPage(getBaseUrl() + "/login");

        HtmlTextInput usernameInput = loginPage.getElementByName("username");
        HtmlPasswordInput passwordInput = loginPage.getElementByName("password");
        HtmlButton loginButton = loginPage.getFirstByXPath("//button[@type='submit']");

        usernameInput.setValue("admin");
        passwordInput.setValue("admin123");

        HtmlPage resultPage = loginButton.click();
        webClient.waitForBackgroundJavaScript(1000);
    }
}
