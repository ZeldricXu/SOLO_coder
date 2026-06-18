package com.designsystem.integration;

import com.designsystem.DesignSystemApplication;
import com.designsystem.common.enums.ApprovalStatus;
import com.designsystem.common.enums.ComponentFramework;
import com.designsystem.entity.ApprovalRequest;
import com.designsystem.entity.Component;
import com.designsystem.entity.Changelog;
import com.designsystem.entity.SysUser;
import com.designsystem.mapper.ApprovalRequestMapper;
import com.designsystem.mapper.ChangelogMapper;
import com.designsystem.mapper.ComponentMapper;
import com.designsystem.mapper.SysUserMapper;
import com.designsystem.security.CustomUserDetails;
import com.designsystem.service.ApprovalService;
import com.designsystem.service.ChangeTrackingService;
import com.designsystem.service.ComponentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = DesignSystemApplication.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("权限审批链路集成测试")
class ApprovalWorkflowIntegrationTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ComponentService componentService;

    @Autowired
    private ChangeTrackingService changeTrackingService;

    @Autowired
    private ComponentMapper componentMapper;

    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private ChangelogMapper changelogMapper;

    @Autowired
    private SysUserMapper userMapper;

    @MockitoBean
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    private SysUser developer;
    private SysUser reviewer;
    private SysUser admin;
    private SysUser designer;

    @BeforeEach
    void setUp() {
        developer = createTestUser("developer", "DEVELOPER");
        reviewer = createTestUser("reviewer", "CODE_REVIEWER");
        admin = createTestUser("admin", "ADMIN");
        designer = createTestUser("designer", "DESIGN_LEAD");
    }

    private SysUser createTestUser(String username, String role) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword("$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2");
        user.setNickname(username);
        user.setEmail(username + "@test.com");
        user.setStatus(1);
        userMapper.insert(user);

        Authentication auth = new UsernamePasswordAuthenticationToken(
                new CustomUserDetails(user.getId(), user.getUsername(), user.getPassword(),
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role),
                        new SimpleGrantedAuthority("component:publish"),
                        new SimpleGrantedAuthority("token:change"),
                        new SimpleGrantedAuthority("version:rollback"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        return user;
    }

    private void setAuthentication(SysUser user, String role) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new CustomUserDetails(user.getId(), user.getUsername(), user.getPassword(),
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role),
                        new SimpleGrantedAuthority("component:publish"),
                        new SimpleGrantedAuthority("token:change"),
                        new SimpleGrantedAuthority("version:rollback"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Nested
    @DisplayName("组件发布审批流程测试")
    class ComponentPublishApprovalTests {

        @Test
        @Order(1)
        @DisplayName("开发者提交组件发布申请")
        void developerShouldSubmitPublishRequest() {
            setAuthentication(developer, "DEVELOPER");

            Component component = createTestComponent("ApprovalButton");

            Changelog changelog1 = createTestChangelog(component.getId(), "feat", "添加新的按钮样式");
            Changelog changelog2 = createTestChangelog(component.getId(), "fix", "修复按钮点击事件");

            ApprovalRequest request = approvalService.createComponentPublishRequest(
                    component.getId(),
                    "1.1.0",
                    "feat: 添加新的按钮样式\nfix: 修复按钮点击事件"
            );

            assertNotNull(request.getId());
            assertEquals(ApprovalStatus.PENDING, request.getStatus());
            assertEquals("COMPONENT_PUBLISH", request.getRequestType());
            assertEquals(component.getId(), request.getTargetId());
            assertNotNull(request.getApproverId());
            assertEquals(developer.getId(), request.getSubmittedBy());
            assertTrue(request.getTitle().contains("ApprovalButton"));
        }

        @Test
        @Order(2)
        @DisplayName("Reviewer审批通过，版本号自动升级")
        void reviewerShouldApproveAndAutoUpgradeVersion() {
            setAuthentication(developer, "DEVELOPER");
            Component component = createTestComponent("AutoUpgradeButton");

            createTestChangelog(component.getId(), "feat", "添加暗黑模式支持");
            createTestChangelog(component.getId(), "fix", "修复样式问题");

            ApprovalRequest request = approvalService.createComponentPublishRequest(
                    component.getId(),
                    "1.0.0",
                    "feat: 添加暗黑模式支持\nfix: 修复样式问题"
            );

            setAuthentication(reviewer, "CODE_REVIEWER");
            when(userMapper.selectById(reviewer.getId())).thenReturn(reviewer);
            when(userMapper.selectById(developer.getId())).thenReturn(developer);

            request.setApproverId(reviewer.getId());
            approvalRequestMapper.updateById(request);

            ApprovalRequest approved = approvalService.approveRequest(request.getId(), "代码质量良好，可以发布");

            assertEquals(ApprovalStatus.APPROVED, approved.getStatus());
            assertNotNull(approved.getApprovedAt());
            assertEquals("代码质量良好，可以发布", approved.getApprovalComment());

            Component publishedComponent = componentService.getComponentById(component.getId());
            assertEquals(1, publishedComponent.getPublished());
            assertNotNull(publishedComponent.getLatestVersion());
            assertTrue(publishedComponent.getLatestVersion().startsWith("1."));

            com.designsystem.common.util.SemverUtil.BumpType bumpType =
                    changeTrackingService.determineBumpType(component.getId());
            assertEquals(com.designsystem.common.util.SemverUtil.BumpType.MINOR, bumpType);
        }

        @Test
        @Order(3)
        @DisplayName("包含BREAKING CHANGE的发布应升级MAJOR版本")
        void breakingChangeShouldTriggerMajorVersionBump() {
            setAuthentication(developer, "DEVELOPER");
            Component component = createTestComponent("BreakingButton");

            Changelog breakingChangelog = new Changelog();
            breakingChangelog.setComponentId(component.getId());
            breakingChangelog.setCommitType("feat");
            breakingChangelog.setCommitSubject("重构组件API");
            breakingChangelog.setBreakingChange("移除了旧的props，使用新的API");
            breakingChangelog.setIncludedInRelease(0);
            breakingChangelog.setCommittedAt(LocalDateTime.now());
            changelogMapper.insert(breakingChangelog);

            ApprovalRequest request = approvalService.createComponentPublishRequest(
                    component.getId(),
                    "1.5.2",
                    "feat!: 重构组件API\n\nBREAKING CHANGE: 移除了旧的props，使用新的API"
            );

            setAuthentication(reviewer, "CODE_REVIEWER");
            request.setApproverId(reviewer.getId());
            approvalRequestMapper.updateById(request);

            ApprovalRequest approved = approvalService.approveRequest(request.getId(), "破坏性变更已确认");

            Component publishedComponent = componentService.getComponentById(component.getId());
            assertEquals("2.0.0", publishedComponent.getLatestVersion());
        }

        @Test
        @Order(4)
        @DisplayName("Reviewer驳回发布申请")
        void reviewerShouldRejectPublishRequest() {
            setAuthentication(developer, "DEVELOPER");
            Component component = createTestComponent("RejectButton");

            ApprovalRequest request = approvalService.createComponentPublishRequest(
                    component.getId(),
                    "1.0.0",
                    "feat: 新增功能"
            );

            setAuthentication(reviewer, "CODE_REVIEWER");
            request.setApproverId(reviewer.getId());
            approvalRequestMapper.updateById(request);

            ApprovalRequest rejected = approvalService.rejectRequest(request.getId(), "存在严重bug，需要修复后重新提交");

            assertEquals(ApprovalStatus.REJECTED, rejected.getStatus());
            assertEquals("存在严重bug，需要修复后重新提交", rejected.getRejectReason());

            Component notPublished = componentService.getComponentById(component.getId());
            assertEquals(0, notPublished.getPublished());
            assertNull(notPublished.getLatestVersion());
        }

        @Test
        @Order(5)
        @DisplayName("审批通过后下游项目应收到变更通知")
        void downstreamProjectsShouldReceiveNotification() {
            setAuthentication(developer, "DEVELOPER");
            Component component = createTestComponent("NotifyButton");

            createTestChangelog(component.getId(), "fix", "修复通知功能");

            ApprovalRequest request = approvalService.createComponentPublishRequest(
                    component.getId(),
                    "1.0.0",
                    "fix: 修复通知功能"
            );

            setAuthentication(reviewer, "CODE_REVIEWER");
            request.setApproverId(reviewer.getId());
            approvalRequestMapper.updateById(request);

            ApprovalRequest approved = approvalService.approveRequest(request.getId(), "OK");

            assertNotNull(approved);
            assertEquals(ApprovalStatus.APPROVED, approved.getStatus());

            Component published = componentService.getComponentById(component.getId());
            assertEquals("1.0.1", published.getLatestVersion());
        }
    }

    @Nested
    @DisplayName("设计令牌变更审批流程测试")
    class TokenChangeApprovalTests {

        @Test
        @DisplayName("设计师提交令牌变更申请")
        void designerShouldSubmitTokenChangeRequest() {
            setAuthentication(designer, "DESIGN_LEAD");

            com.designsystem.entity.DesignToken token = new com.designsystem.entity.DesignToken();
            token.setTokenName("--color-test-primary");
            token.setDisplayName("测试主色");
            token.setTokenType(com.designsystem.common.enums.TokenType.COLOR);
            token.setTokenLevel(com.designsystem.common.enums.TokenLevel.SEMANTIC);
            token.setBaseValue("#3b82f6");
            token.setStatus(1);

            com.designsystem.service.DesignTokenService tokenService =
                    com.designsystem.common.ApplicationContextProvider.getApplicationContext()
                            .getBean(com.designsystem.service.DesignTokenService.class);

            assertDoesNotThrow(() -> {
                ApprovalRequest request = approvalService.createTokenChangeRequest(
                        1L,
                        "UPDATE",
                        "将主色从#3b82f6调整为#2563eb"
                );

                assertNotNull(request);
                assertEquals(ApprovalStatus.PENDING, request.getStatus());
                assertTrue(request.getTitle().contains("设计令牌变更申请"));
            });
        }

        @Test
        @DisplayName("设计负责人审批令牌变更")
        void designLeadShouldApproveTokenChange() {
            setAuthentication(designer, "DESIGN_LEAD");

            ApprovalRequest request = new ApprovalRequest();
            request.setRequestType("TOKEN_UPDATE");
            request.setTargetId(1L);
            request.setTargetType("TOKEN");
            request.setTitle("令牌变更测试");
            request.setStatus(ApprovalStatus.PENDING);
            request.setSubmittedBy(designer.getId());
            request.setApproverId(designer.getId());
            request.setSubmittedAt(LocalDateTime.now());
            approvalRequestMapper.insert(request);

            ApprovalRequest approved = approvalService.approveRequest(request.getId(), "颜色调整符合设计规范");

            assertEquals(ApprovalStatus.APPROVED, approved.getStatus());
            assertNotNull(approved.getApprovedAt());
        }
    }

    @Nested
    @DisplayName("权限控制测试")
    class PermissionControlTests {

        @Test
        @DisplayName("普通开发者不能审批组件发布")
        void developerShouldNotApproveRequests() {
            setAuthentication(developer, "DEVELOPER");

            Component component = createTestComponent("PermissionButton");
            ApprovalRequest request = approvalService.createComponentPublishRequest(
                    component.getId(),
                    "1.0.0",
                    "feat: 测试"
            );
            request.setApproverId(reviewer.getId());
            approvalRequestMapper.updateById(request);

            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> {
                approvalService.approveRequest(request.getId(), "越权审批");
            });
        }

        @Test
        @DisplayName("只有管理员可以版本回滚")
        void onlyAdminCanRollbackVersion() {
            setAuthentication(admin, "ADMIN");

            Component component = createTestComponent("RollbackButton");
            component.setLatestVersion("2.0.0");
            componentMapper.updateById(component);

            assertDoesNotThrow(() -> {
                approvalService.rollbackVersion(component.getId(), "1.0.0");
            });

            Component rolledBack = componentService.getComponentById(component.getId());
            assertEquals("1.0.0", rolledBack.getLatestVersion());
        }

        @Test
        @DisplayName("开发者不能执行版本回滚")
        void developerShouldNotRollbackVersion() {
            setAuthentication(developer, "DEVELOPER");

            Component component = createTestComponent("NoRollbackButton");
            component.setLatestVersion("2.0.0");
            componentMapper.updateById(component);

            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> {
                approvalService.rollbackVersion(component.getId(), "1.0.0");
            });
        }
    }

    @Nested
    @DisplayName("审批列表查询测试")
    class ApprovalListTests {

        @Test
        @DisplayName("查询待我审批的列表")
        void shouldReturnPendingApprovalsForCurrentUser() {
            setAuthentication(reviewer, "CODE_REVIEWER");

            for (int i = 0; i < 5; i++) {
                Component component = createTestComponent("ListComponent" + i);

                setAuthentication(developer, "DEVELOPER");
                ApprovalRequest request = approvalService.createComponentPublishRequest(
                        component.getId(),
                        "1.0.0",
                        "feat: 测试组件" + i
                );

                request.setApproverId(reviewer.getId());
                approvalRequestMapper.updateById(request);
            }

            setAuthentication(reviewer, "CODE_REVIEWER");

            com.designsystem.common.PageQuery query = new com.designsystem.common.PageQuery();
            query.setPageNum(1);
            query.setPageSize(10);

            var page = approvalService.getApprovalPage(query, "PENDING", "COMPONENT_PUBLISH");

            assertNotNull(page);
            assertTrue(page.getTotal() >= 5);

            List<ApprovalRequest> pending = approvalService.getPendingApprovals();
            assertTrue(pending.size() >= 5);
        }
    }

    private Component createTestComponent(String name) {
        Component component = new Component();
        component.setName(name);
        component.setDisplayName(name + "显示名");
        component.setCategory("测试组件");
        component.setFramework(ComponentFramework.REACT.getCode());
        component.setDescription("审批流程测试组件");
        component.setMaintainerId(developer.getId());
        return componentService.createComponent(component);
    }

    private Changelog createTestChangelog(Long componentId, String type, String subject) {
        Changelog changelog = new Changelog();
        changelog.setComponentId(componentId);
        changelog.setCommitType(type);
        changelog.setCommitSubject(subject);
        changelog.setIncludedInRelease(0);
        changelog.setCommittedAt(LocalDateTime.now());
        changelog.setAuthor("test");
        changelog.setAuthorEmail("test@test.com");
        changelogMapper.insert(changelog);
        return changelog;
    }
}
