package com.chaoslab.modules.mtls.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.BaseTest;
import com.chaoslab.entity.MtlsCertificate;
import com.chaoslab.entity.MtlsRevocationList;
import com.chaoslab.entity.MtlsRotationPolicy;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.MtlsCertificateMapper;
import com.chaoslab.mapper.MtlsRevocationListMapper;
import com.chaoslab.mapper.MtlsRotationPolicyMapper;
import com.chaoslab.modules.mtls.dto.CertificateIssueRequest;
import com.chaoslab.modules.mtls.dto.RevocationRequest;
import com.chaoslab.modules.mtls.strategy.CertificateStrategyRegistry;
import com.chaoslab.modules.mtls.strategy.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("MtlsCertificateStrategyService 单元测试")
class MtlsCertificateStrategyServiceTest extends BaseTest {

    @Mock
    private MtlsCertificateMapper certificateMapper;

    @Mock
    private MtlsRotationPolicyMapper rotationPolicyMapper;

    @Mock
    private MtlsRevocationListMapper revocationListMapper;

    @Spy
    private DefaultCertificateStrategy defaultStrategy;

    @Spy
    private StrictSecurityStrategy strictSecurityStrategy;

    @Spy
    private TestingEnvironmentStrategy testingEnvironmentStrategy;

    @Spy
    private ExtendedValidationStrategy extendedValidationStrategy;

    @Spy
    private AuditLoggingStrategy auditLoggingStrategy;

    @InjectMocks
    private CertificateStrategyRegistry strategyRegistry;

    @InjectMocks
    private MtlsCertificateStrategyService strategyService;

    private final Map<String, MtlsCertificate> certStore = new ConcurrentHashMap<>();
    private final Map<String, MtlsRotationPolicy> policyStore = new ConcurrentHashMap<>();
    private final Map<String, MtlsRevocationList> revocationStore = new ConcurrentHashMap<>();

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        certStore.clear();
        policyStore.clear();
        revocationStore.clear();
        setupMockBehaviors();
        strategyRegistry.init();
    }

    private void setupMockBehaviors() {
        when(certificateMapper.insert(any(MtlsCertificate.class))).thenAnswer(invocation -> {
            MtlsCertificate cert = invocation.getArgument(0);
            certStore.put(cert.getCertId(), cert);
            return 1;
        });

        when(certificateMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return certStore.values().stream().findFirst().orElse(null);
        });

        when(certificateMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return new ArrayList<>(certStore.values());
        });

        when(certificateMapper.updateById(any(MtlsCertificate.class))).thenAnswer(invocation -> {
            MtlsCertificate cert = invocation.getArgument(0);
            certStore.put(cert.getCertId(), cert);
            return 1;
        });

        when(rotationPolicyMapper.insert(any(MtlsRotationPolicy.class))).thenAnswer(invocation -> {
            MtlsRotationPolicy policy = invocation.getArgument(0);
            policyStore.put(policy.getPolicyId(), policy);
            return 1;
        });

        when(rotationPolicyMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return policyStore.values().stream().findFirst().orElse(null);
        });

        when(revocationListMapper.insert(any(MtlsRevocationList.class))).thenAnswer(invocation -> {
            MtlsRevocationList revocation = invocation.getArgument(0);
            revocationStore.put(revocation.getRevocationId(), revocation);
            return 1;
        });

        when(revocationListMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return revocationStore.values().stream().findFirst().orElse(null);
        });

        when(revocationListMapper.selectCount(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return (long) revocationStore.size();
        });
    }

    // ==================== 策略管理测试 ====================

    @Nested
    @DisplayName("策略管理测试")
    class StrategyManagementTests {

        @Test
        @DisplayName("列出所有策略 - 成功")
        void listStrategies_Success() {
            Mono<Map<String, Object>> result = strategyService.listStrategies();

            StepVerifier.create(result)
                    .expectNextMatches(stats -> {
                        assertThat((Integer) stats.get("totalStrategies")).isGreaterThanOrEqualTo(5);
                        assertThat((Integer) stats.get("activeStrategies")).isGreaterThanOrEqualTo(2);
                        List<Map<String, Object>> strategies = (List<Map<String, Object>>) stats.get("strategies");
                        assertThat(strategies).extracting("name")
                                .contains("DEFAULT", "STRICT_SECURITY", "TESTING_ENV",
                                        "EXTENDED_VALIDATION", "AUDIT_LOGGING");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("激活策略 - 成功")
        void activateStrategy_Success() {
            Mono<String> result = strategyService.activateStrategy(
                    "STRICT_SECURITY", "admin", "安全增强");

            StepVerifier.create(result)
                    .expectNextMatches(message -> {
                        assertThat(message).contains("activated successfully");
                        assertThat(strategyRegistry.getActiveStrategies())
                                .extracting("name")
                                .contains("STRICT_SECURITY");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("停用策略 - 成功")
        void deactivateStrategy_Success() {
            strategyRegistry.activateStrategy("TESTING_ENV");
            assertThat(strategyRegistry.getActiveStrategies())
                    .extracting("name")
                    .contains("TESTING_ENV");

            Mono<String> result = strategyService.deactivateStrategy(
                    "TESTING_ENV", "admin", "测试完成");

            StepVerifier.create(result)
                    .expectNextMatches(message -> {
                        assertThat(message).contains("deactivated successfully");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("停用DEFAULT策略 - 失败")
        void deactivateStrategy_Default_Failure() {
            Mono<String> result = strategyService.deactivateStrategy(
                    "DEFAULT", "admin", "尝试停用默认策略");

            StepVerifier.create(result)
                    .expectErrorSatisfies(e -> {
                        assertThat(e).hasMessageContaining("Cannot deactivate DEFAULT");
                    })
                    .verify();
        }

        @Test
        @DisplayName("切换策略 - 成功")
        void switchStrategy_Success() {
            Mono<String> result = strategyService.switchStrategy(
                    "TESTING_ENV", "STRICT_SECURITY", "admin", "环境切换");

            StepVerifier.create(result)
                    .expectNextMatches(message -> {
                        assertThat(message).contains("switched from");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("获取执行链 - 包含优先策略")
        void getExecutionChain_IncludesPreferred() {
            strategyRegistry.activateStrategy("STRICT_SECURITY");

            var chain = strategyRegistry.getExecutionChain("STRICT_SECURITY");
            assertThat(chain).isNotEmpty();
            assertThat(chain.get(0).getName()).isEqualTo("STRICT_SECURITY");
        }
    }

    // ==================== 使用策略的证书操作测试 ====================

    @Nested
    @DisplayName("策略证书操作测试")
    class StrategyCertificateTests {

        @Test
        @DisplayName("使用默认策略签发证书 - 成功")
        void issueCertificateWithStrategy_Default_Success() {
            CertificateIssueRequest request = new CertificateIssueRequest();
            request.setCommonName("test-default.chaoslab.local");
            request.setOrganization("ChaosLab");
            request.setCountry("CN");
            request.setValidityDays(365);

            Mono<com.chaoslab.modules.mtls.dto.CertificateResponse> result =
                    strategyService.issueCertificateWithStrategy(request, "DEFAULT");

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getCertId()).isNotNull().startsWith("cert-");
                        assertThat(response.getCommonName()).isEqualTo("test-default.chaoslab.local");
                        assertThat(response.getStatus()).isEqualTo("active");
                        assertThat(response.getCertificatePem()).contains("BEGIN CERTIFICATE");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("使用严格安全策略签发证书 - 90天有效期")
        void issueCertificateWithStrategy_StrictSecurity_Success() {
            strategyRegistry.activateStrategy("STRICT_SECURITY");

            CertificateIssueRequest request = new CertificateIssueRequest();
            request.setCommonName("test-strict.chaoslab.local");
            request.setOrganization("ChaosLab");
            request.setCountry("CN");
            request.setValidityDays(365);

            Mono<com.chaoslab.modules.mtls.dto.CertificateResponse> result =
                    strategyService.issueCertificateWithStrategy(request, "STRICT_SECURITY");

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getNotAfter()).isBeforeOrEqualTo(
                                LocalDateTime.now().plusDays(90).plusHours(1));
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("使用测试环境策略签发证书 - 30天有效期")
        void issueCertificateWithStrategy_TestingEnv_Success() {
            strategyRegistry.activateStrategy("TESTING_ENV");

            CertificateIssueRequest request = new CertificateIssueRequest();
            request.setCommonName("test-env.chaoslab.local");
            request.setOrganization("ChaosLab");
            request.setCountry("CN");
            request.setValidityDays(365);

            Mono<com.chaoslab.modules.mtls.dto.CertificateResponse> result =
                    strategyService.issueCertificateWithStrategy(request, "TESTING_ENV");

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getNotAfter()).isBeforeOrEqualTo(
                                LocalDateTime.now().plusDays(30).plusHours(1));
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("使用扩展验证策略签发证书 - 验证必填字段")
        void issueCertificateWithStrategy_ExtendedValidation_Success() {
            strategyRegistry.activateStrategy("EXTENDED_VALIDATION");

            CertificateIssueRequest request = new CertificateIssueRequest();
            request.setCommonName("test-ev.chaoslab.local");
            request.setOrganization("ChaosLab Inc");
            request.setCountry("CN");
            request.setOrganizationalUnit("Engineering");

            Mono<com.chaoslab.modules.mtls.dto.CertificateResponse> result =
                    strategyService.issueCertificateWithStrategy(request, "EXTENDED_VALIDATION");

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getCommonName()).isEqualTo("test-ev.chaoslab.local");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("使用扩展验证策略 - 缺少必填字段失败")
        void issueCertificateWithStrategy_ExtendedValidation_MissingFields() {
            strategyRegistry.activateStrategy("EXTENDED_VALIDATION");

            CertificateIssueRequest request = new CertificateIssueRequest();
            request.setCommonName("test-ev.chaoslab.local");
            request.setOrganization(null);
            request.setCountry(null);

            Mono<com.chaoslab.modules.mtls.dto.CertificateResponse> result =
                    strategyService.issueCertificateWithStrategy(request, "EXTENDED_VALIDATION");

            StepVerifier.create(result)
                    .expectErrorSatisfies(e -> {
                        assertThat(e).hasMessageContaining("requires");
                    })
                    .verify();
        }

        @Test
        @DisplayName("使用审计策略吊销证书 - 记录审计日志")
        void revokeCertificateWithStrategy_AuditLogging_Success() {
            CertificateIssueRequest issueRequest = new CertificateIssueRequest();
            issueRequest.setCommonName("test-audit-revoke.chaoslab.local");
            issueRequest.setOrganization("ChaosLab");
            issueRequest.setCountry("CN");
            var certResponse = strategyService.issueCertificateWithStrategy(issueRequest, "DEFAULT").block();
            assertNotNull(certResponse);

            MtlsCertificate cert = certStore.values().iterator().next();
            when(certificateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cert);

            strategyRegistry.activateStrategy("AUDIT_LOGGING");

            RevocationRequest revocationRequest = new RevocationRequest();
            revocationRequest.setCertId(cert.getCertId());
            revocationRequest.setReason("Key compromise");
            revocationRequest.setRevokedBy("security-admin");

            Mono<MtlsRevocationList> result =
                    strategyService.revokeCertificateWithStrategy(revocationRequest, "AUDIT_LOGGING");

            StepVerifier.create(result)
                    .expectNextMatches(revocation -> {
                        assertThat(revocation.getRevocationId()).isNotNull().startsWith("rev-");
                        assertThat(revocation.getReason()).isEqualTo("Key compromise");
                        assertThat(certStore.get(cert.getCertId()).getStatus()).isEqualTo("revoked");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("使用多策略执行链 - 按优先级排序")
        void issueCertificateWithStrategy_MultipleStrategies_Ordered() {
            strategyRegistry.activateStrategy("STRICT_SECURITY");
            strategyRegistry.activateStrategy("AUDIT_LOGGING");

            var chain = strategyRegistry.getExecutionChain(null);
            assertThat(chain).hasSizeGreaterThanOrEqualTo(3);

            List<Integer> priorities = chain.stream()
                    .map(com.chaoslab.modules.mtls.strategy.CertificateStrategy::getPriority)
                    .toList();

            for (int i = 0; i < priorities.size() - 1; i++) {
                assertThat(priorities.get(i)).isLessThanOrEqualTo(priorities.get(i + 1));
            }
        }
    }

    // ==================== 策略优先级测试 ====================

    @Nested
    @DisplayName("策略优先级测试")
    class StrategyPriorityTests {

        @Test
        @DisplayName("策略按优先级排序 - 正确")
        void getAllStrategies_SortedByPriority() {
            var strategies = strategyRegistry.getAllStrategies();
            assertThat(strategies).isNotEmpty();

            for (int i = 0; i < strategies.size() - 1; i++) {
                assertThat(strategies.get(i).getPriority())
                        .isLessThanOrEqualTo(strategies.get(i + 1).getPriority());
            }
        }

        @Test
        @DisplayName("AUDIT_LOGGING优先级最高 - 最先执行")
        void auditLogging_HighestPriority() {
            var strategies = strategyRegistry.getAllStrategies();
            var auditStrategy = strategies.stream()
                    .filter(s -> "AUDIT_LOGGING".equals(s.getName()))
                    .findFirst();

            assertThat(auditStrategy).isPresent();
            assertThat(auditStrategy.get().getPriority()).isEqualTo(5);
        }

        @Test
        @DisplayName("DEFAULT优先级最低 - 最后执行")
        void defaultStrategy_LowestPriority() {
            var strategies = strategyRegistry.getAllStrategies();
            var defaultStrategy = strategies.stream()
                    .filter(s -> "DEFAULT".equals(s.getName()))
                    .findFirst();

            assertThat(defaultStrategy).isPresent();
            assertThat(defaultStrategy.get().getPriority()).isEqualTo(Integer.MAX_VALUE);
        }
    }

    // ==================== 策略切换测试 ====================

    @Nested
    @DisplayName("策略切换测试")
    class StrategySwitchTests {

        @Test
        @DisplayName("运行时切换策略 - 立即生效")
        void switchStrategy_Runtime_EffectiveImmediately() {
            CertificateIssueRequest request = new CertificateIssueRequest();
            request.setCommonName("test-switch.chaoslab.local");
            request.setOrganization("ChaosLab");
            request.setCountry("CN");
            request.setValidityDays(365);

            strategyRegistry.activateStrategy("TESTING_ENV");
            var cert1 = strategyService.issueCertificateWithStrategy(request, "TESTING_ENV").block();
            assertNotNull(cert1);

            strategyRegistry.deactivateStrategy("TESTING_ENV");
            strategyRegistry.activateStrategy("STRICT_SECURITY");

            var cert2 = strategyService.issueCertificateWithStrategy(request, "STRICT_SECURITY").block();
            assertNotNull(cert2);

            assertThat(cert1.getNotAfter()).isBeforeOrEqualTo(
                    LocalDateTime.now().plusDays(30).plusHours(1));
            assertThat(cert2.getNotAfter()).isBeforeOrEqualTo(
                    LocalDateTime.now().plusDays(90).plusHours(1));
        }

        @Test
        @DisplayName("策略切换不影响已有证书")
        void switchStrategy_DoesNotAffectExistingCerts() {
            CertificateIssueRequest request = new CertificateIssueRequest();
            request.setCommonName("test-existing.chaoslab.local");
            request.setOrganization("ChaosLab");
            request.setCountry("CN");

            var cert = strategyService.issueCertificateWithStrategy(request, "DEFAULT").block();
            assertNotNull(cert);
            String originalStatus = cert.getStatus();

            strategyRegistry.activateStrategy("STRICT_SECURITY");

            var storedCert = certStore.get(cert.getCertId());
            assertThat(storedCert.getStatus()).isEqualTo(originalStatus);
        }
    }
}
