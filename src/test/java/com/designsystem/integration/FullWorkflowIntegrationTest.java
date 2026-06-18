package com.designsystem.integration;

import com.designsystem.DesignSystemApplication;
import com.designsystem.common.enums.ComponentFramework;
import com.designsystem.common.enums.ExportFormat;
import com.designsystem.common.enums.TokenLevel;
import com.designsystem.common.enums.TokenType;
import com.designsystem.entity.Component;
import com.designsystem.entity.ComponentDoc;
import com.designsystem.entity.ComponentProp;
import com.designsystem.entity.ComponentVersion;
import com.designsystem.entity.DesignToken;
import com.designsystem.mapper.ComponentDocMapper;
import com.designsystem.mapper.ComponentPropMapper;
import com.designsystem.mapper.ComponentVersionMapper;
import com.designsystem.service.ChangeTrackingService;
import com.designsystem.service.ComponentService;
import com.designsystem.service.DesignTokenService;
import com.designsystem.service.DocumentationService;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = DesignSystemApplication.class)
@Testcontainers
@Import(TestcontainersConfig.class)
@Transactional
@Rollback
@DisplayName("Testcontainers完整链路集成测试")
class FullWorkflowIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.3.0"))
            .withDatabaseName("design_system_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.13.4"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @Container
    static final RabbitMQContainer rabbitmq = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.15.0-management"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        registry.add("spring.elasticsearch.uris", elasticsearch::getHttpHostAddress);

        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @Autowired
    private ComponentService componentService;

    @Autowired
    private DesignTokenService tokenService;

    @Autowired
    private DocumentationService documentationService;

    @Autowired
    private ChangeTrackingService changeTrackingService;

    @Autowired
    private ComponentVersionMapper versionMapper;

    @Autowired
    private ComponentPropMapper propMapper;

    @Autowired
    private ComponentDocMapper docMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private Long testComponentId;
    private Long testVersionId;

    @BeforeEach
    void setUp() {
        tokenService.init();
    }

    @Test
    @Order(1)
    @DisplayName("完整链路测试：创建组件→上传源码→文档自动生成→定义令牌→导出格式→影响分析→发布组件→CHANGELOG生成")
    void testCompleteWorkflow() throws Exception {
        Component component = createTestComponent();
        assertNotNull(component.getId());
        testComponentId = component.getId();

        ComponentVersion version = createTestVersion(component.getId());
        assertNotNull(version.getId());
        testVersionId = version.getId();

        List<ComponentProp> props = extractAndVerifyProps();
        assertTrue(props.size() > 0);

        List<ComponentDoc> docs = extractAndVerifyDocs();
        assertTrue(docs.size() > 0);

        DesignToken baseToken = createAndVerifyBaseToken();
        DesignToken semanticToken = createAndVerifySemanticToken(baseToken);
        createAndVerifyComponentToken(semanticToken);

        verifyTokenExport();

        verifyImpactAnalysis(baseToken.getId());

        publishAndVerifyComponent();

        verifyChangelogGeneration();
    }

    private Component createTestComponent() {
        Component component = new Component();
        component.setName("TestButton");
        component.setDisplayName("测试按钮");
        component.setCategory("基础组件");
        component.setFramework(ComponentFramework.REACT.getCode());
        component.setTags("button,ui,form");
        component.setDescription("用于触发操作的按钮组件");
        component.setMaintainerId(1L);
        component.setGitRepository("https://github.com/test/design-system");
        component.setNpmPackage("@design-system/button");

        return componentService.createComponent(component);
    }

    private ComponentVersion createTestVersion(Long componentId) {
        ComponentVersion version = new ComponentVersion();
        version.setComponentId(componentId);
        version.setVersion("1.0.0");
        version.setChangelog("Initial release");
        version.setReleaseNotes("第一个正式版本");
        version.setIsLatest(1);
        version.setIsPrerelease(0);

        return componentService.createVersion(version);
    }

    private List<ComponentProp> extractAndVerifyProps() throws Exception {
        String reactSource = """
                /**
                 * 按钮组件
                 * @title 按钮 Button
                 */
                interface ButtonProps {
                  /** 按钮类型 */
                  type?: 'primary' | 'secondary' | 'danger';
                  /** 按钮尺寸 */
                  size?: 'small' | 'medium' | 'large';
                  /** 是否禁用 */
                  disabled?: boolean;
                  /** 点击事件 */
                  onClick?: () => void;
                  /** 按钮文本 */
                  children?: React.ReactNode;
                }
                
                const Button: React.FC<ButtonProps> = (props) => {
                  return <button className={\`btn btn-\${props.type}\`}>{props.children}</button>;
                };
                
                Button.defaultProps = {
                  type: 'secondary',
                  size: 'medium',
                  disabled: false
                };
                
                export default Button;
                """;

        MultipartFile file = new MockMultipartFile(
                "source", "Button.tsx", "text/typescript",
                reactSource.getBytes(StandardCharsets.UTF_8)
        );

        List<ComponentProp> props = documentationService.extractPropsFromSource(testVersionId, file, "react");

        assertEquals(5, props.size());

        ComponentProp typeProp = props.stream().filter(p -> "type".equals(p.getName())).findFirst().orElseThrow();
        assertEquals("'primary' | 'secondary' | 'danger'", typeProp.getPropType());
        assertEquals("'secondary'", typeProp.getDefaultValue());
        assertEquals("按钮类型", typeProp.getDescription().trim());

        ComponentProp disabledProp = props.stream().filter(p -> "disabled".equals(p.getName())).findFirst().orElseThrow();
        assertEquals("boolean", disabledProp.getPropType());
        assertEquals("false", disabledProp.getDefaultValue());

        return props;
    }

    private List<ComponentDoc> extractAndVerifyDocs() throws Exception {
        String sourceWithDocs = """
                /**
                 * 按钮组件
                 *
                 * 这是一个通用的按钮组件，支持多种样式和状态。
                 *
                 * @title 按钮 Button
                 * @example
                 * <Button type="primary" onClick={() => console.log('clicked')}>
                 *   点击我
                 * </Button>
                 */
                interface ButtonProps {
                  /** 按钮类型 */
                  type?: string;
                }
                
                const Button = (props) => <button {...props} />;
                """;

        MultipartFile file = new MockMultipartFile(
                "source", "Button.tsx", "text/typescript",
                sourceWithDocs.getBytes(StandardCharsets.UTF_8)
        );

        List<ComponentDoc> docs = documentationService.extractDocsFromSource(testVersionId, file);

        assertFalse(docs.isEmpty());
        assertTrue(docs.stream().anyMatch(d ->
                d.getTitle() != null && d.getTitle().contains("按钮")));

        return docs;
    }

    private DesignToken createAndVerifyBaseToken() {
        DesignToken baseToken = new DesignToken();
        baseToken.setTokenName("--base-blue-500");
        baseToken.setDisplayName("基础蓝色500");
        baseToken.setTokenType(TokenType.COLOR);
        baseToken.setTokenLevel(TokenLevel.BASE);
        baseToken.setBaseValue("#3b82f6");
        baseToken.setCategory("颜色/蓝色");
        baseToken.setDescription("基础蓝色主色");

        DesignToken created = tokenService.createToken(baseToken);
        assertNotNull(created.getId());

        DesignToken found = tokenService.getTokenById(created.getId());
        assertEquals("--base-blue-500", found.getTokenName());
        assertEquals("#3b82f6", found.getBaseValue());

        return created;
    }

    private DesignToken createAndVerifySemanticToken(DesignToken baseToken) {
        DesignToken semanticToken = new DesignToken();
        semanticToken.setTokenName("--color-primary");
        semanticToken.setDisplayName("主色调");
        semanticToken.setTokenType(TokenType.COLOR);
        semanticToken.setTokenLevel(TokenLevel.SEMANTIC);
        semanticToken.setInheritsFrom(baseToken.getTokenName());
        semanticToken.setCategory("颜色/语义化");
        semanticToken.setDescription("主要操作按钮颜色");

        DesignToken created = tokenService.createToken(semanticToken);
        assertNotNull(created.getId());

        String resolvedValue = tokenService.resolveTokenValue("--color-primary");
        assertEquals("#3b82f6", resolvedValue);

        return created;
    }

    private void createAndVerifyComponentToken(DesignToken semanticToken) {
        DesignToken componentToken = new DesignToken();
        componentToken.setTokenName("--button-bg-color");
        componentToken.setDisplayName("按钮背景色");
        componentToken.setTokenType(TokenType.COLOR);
        componentToken.setTokenLevel(TokenLevel.COMPONENT);
        componentToken.setInheritsFrom(semanticToken.getTokenName());
        componentToken.setCategory("颜色/组件级");
        componentToken.setDescription("按钮组件背景颜色");

        DesignToken created = tokenService.createToken(componentToken);
        assertNotNull(created.getId());

        String resolvedValue = tokenService.resolveTokenValue("--button-bg-color");
        assertEquals("#3b82f6", resolvedValue);
    }

    private void verifyTokenExport() {
        String cssExport = tokenService.exportTokens(ExportFormat.CSS, null, null);
        assertTrue(cssExport.contains("--base-blue-500: #3b82f6"));
        assertTrue(cssExport.contains(":root {"));

        String jsExport = tokenService.exportTokens(ExportFormat.JS, null, null);
        assertTrue(jsExport.contains("export const designTokens"));
        assertTrue(jsExport.contains("BASE_BLUE_500: '#3b82f6'"));

        String jsonExport = tokenService.exportTokens(ExportFormat.JSON, null, null);
        assertTrue(jsonExport.contains("\"name\": \"--base-blue-500\""));
        assertTrue(jsonExport.contains("\"value\": \"#3b82f6\""));
    }

    private void verifyImpactAnalysis(Long baseTokenId) {
        Map<String, Object> impact = tokenService.getTokenImpactAnalysis(baseTokenId);

        assertNotNull(impact.get("token"));
        assertNotNull(impact.get("affectedTokens"));
        assertNotNull(impact.get("affectedComponents"));
        assertNotNull(impact.get("changeHistory"));

        @SuppressWarnings("unchecked")
        List<DesignToken> affectedTokens = (List<DesignToken>) impact.get("affectedTokens");
        assertTrue(affectedTokens.size() >= 2);
        assertTrue(affectedTokens.stream().anyMatch(t ->
                "--color-primary".equals(t.getTokenName())));
        assertTrue(affectedTokens.stream().anyMatch(t ->
                "--button-bg-color".equals(t.getTokenName())));
    }

    private void publishAndVerifyComponent() {
        componentService.publishComponent(testComponentId, "1.1.0");

        Component published = componentService.getComponentById(testComponentId);
        assertEquals("1.1.0", published.getLatestVersion());
        assertEquals(1, published.getPublished());

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<com.designsystem.entity.Changelog> changelogs =
                    changeTrackingService.getChangelogsByComponentId(testComponentId);
            assertNotNull(changelogs);
        });
    }

    private void verifyChangelogGeneration() {
        String changelog = changeTrackingService.generateReleaseChangelog(testComponentId, "1.1.0");

        assertNotNull(changelog);
        assertTrue(changelog.contains("# 1.1.0"));
        assertTrue(changelog.contains("##"));

        com.designsystem.common.util.SemverUtil.BumpType bumpType =
                changeTrackingService.determineBumpType(testComponentId);
        assertNotNull(bumpType);
    }

    @Test
    @Order(2)
    @DisplayName("修改基础色后令牌继承链应全部更新")
    void testTokenInheritanceUpdate() {
        DesignToken baseBlue = new DesignToken();
        baseBlue.setTokenName("--test-blue-500");
        baseBlue.setTokenType(TokenType.COLOR);
        baseBlue.setTokenLevel(TokenLevel.BASE);
        baseBlue.setBaseValue("#3b82f6");
        baseBlue = tokenService.createToken(baseBlue);

        DesignToken primaryColor = new DesignToken();
        primaryColor.setTokenName("--test-color-primary");
        primaryColor.setTokenType(TokenType.COLOR);
        primaryColor.setTokenLevel(TokenLevel.SEMANTIC);
        primaryColor.setInheritsFrom("--test-blue-500");
        primaryColor = tokenService.createToken(primaryColor);

        DesignToken buttonBg = new DesignToken();
        buttonBg.setTokenName("--test-button-bg");
        buttonBg.setTokenType(TokenType.COLOR);
        buttonBg.setTokenLevel(TokenLevel.COMPONENT);
        buttonBg.setInheritsFrom("--test-color-primary");
        buttonBg = tokenService.createToken(buttonBg);

        assertEquals("#3b82f6", tokenService.resolveTokenValue("--test-blue-500"));
        assertEquals("#3b82f6", tokenService.resolveTokenValue("--test-color-primary"));
        assertEquals("#3b82f6", tokenService.resolveTokenValue("--test-button-bg"));

        baseBlue.setBaseValue("#2563eb");
        tokenService.updateToken(baseBlue);

        assertEquals("#2563eb", tokenService.resolveTokenValue("--test-blue-500"));
        assertEquals("#2563eb", tokenService.resolveTokenValue("--test-color-primary"));
        assertEquals("#2563eb", tokenService.resolveTokenValue("--test-button-bg"));
    }

    @Test
    @Order(3)
    @DisplayName("循环引用应被检测并阻断")
    void testCircularReferenceDetection() {
        DesignToken tokenA = new DesignToken();
        tokenA.setTokenName("--circular-a");
        tokenA.setTokenType(TokenType.COLOR);
        tokenA.setTokenLevel(TokenLevel.SEMANTIC);
        tokenA.setInheritsFrom("--circular-b");
        tokenA = tokenService.createToken(tokenA);

        DesignToken tokenB = new DesignToken();
        tokenB.setTokenName("--circular-b");
        tokenB.setTokenType(TokenType.COLOR);
        tokenB.setTokenLevel(TokenLevel.SEMANTIC);
        tokenB.setInheritsFrom("--circular-a");
        tokenB = tokenService.createToken(tokenB);

        tokenA.setInheritsFrom("--circular-b");

        assertThrows(IllegalArgumentException.class, () -> {
            tokenService.updateToken(tokenA);
        });

        boolean hasCycle = tokenService.checkCircularReference("--circular-a", "--circular-b");
        assertTrue(hasCycle);
    }
}
