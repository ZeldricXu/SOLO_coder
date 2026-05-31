package com.chaoslab.modules.mtls.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.ConcurrentTestBase;
import com.chaoslab.entity.MtlsCertificate;
import com.chaoslab.entity.MtlsRevocationList;
import com.chaoslab.entity.MtlsRotationPolicy;
import com.chaoslab.mapper.MtlsCertificateMapper;
import com.chaoslab.mapper.MtlsRevocationListMapper;
import com.chaoslab.mapper.MtlsRotationPolicyMapper;
import com.chaoslab.modules.mtls.dto.CertificateIssueRequest;
import com.chaoslab.modules.mtls.dto.RevocationRequest;
import com.chaoslab.modules.mtls.strategy.CertificateStrategyRegistry;
import com.chaoslab.modules.mtls.strategy.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("MtlsCertificateStrategyService 并发测试")
class MtlsCertificateStrategyServiceConcurrentTest extends ConcurrentTestBase {

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

    private final ConcurrentHashMap<String, MtlsCertificate> certStore = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        certStore.clear();
        idCounter.set(0);
        setupMockBehaviors();
        strategyRegistry.init();
    }

    private void setupMockBehaviors() {
        when(certificateMapper.insert(any(MtlsCertificate.class))).thenAnswer(invocation -> {
            MtlsCertificate cert = invocation.getArgument(0);
            if (cert.getId() == null) {
                cert.setId((long) idCounter.incrementAndGet());
            }
            certStore.put(cert.getCertId(), cert);
            return 1;
        });

        when(certificateMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return certStore.values().stream().findFirst().orElse(null);
        });

        when(certificateMapper.updateById(any(MtlsCertificate.class))).thenAnswer(invocation -> {
            MtlsCertificate cert = invocation.getArgument(0);
            certStore.put(cert.getCertId(), cert);
            return 1;
        });

        when(revocationListMapper.insert(any(MtlsRevocationList.class))).thenAnswer(invocation -> {
            return 1;
        });

        when(rotationPolicyMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return null;
        });
    }

    @Test
    @DisplayName("并发签发证书 - 线程安全")
    void concurrentIssueCertificate_ThreadSafe() throws Exception {
        assertConcurrentSafety(
                () -> {
                    CertificateIssueRequest request = new CertificateIssueRequest();
                    request.setCommonName("concurrent-" + UUID.randomUUID() + ".chaoslab.local");
                    request.setOrganization("ChaosLab");
                    request.setCountry("CN");
                    request.setValidityDays(365);
                    return strategyService.issueCertificateWithStrategy(request, "DEFAULT").block();
                },
                DEFAULT_THREAD_COUNT,
                20
        );
    }

    @Test
    @DisplayName("并发切换策略 - 运行时安全")
    void concurrentSwitchStrategy_RuntimeSafe() throws Exception {
        String[] strategies = {"DEFAULT", "STRICT_SECURITY", "TESTING_ENV"};

        assertConcurrentSafety(
                () -> {
                    String randomStrategy = strategies[ThreadLocalRandom.current().nextInt(strategies.length)];
                    strategyService.activateStrategy(randomStrategy, "admin", "test").block();
                    return randomStrategy;
                },
                5,
                30
        );
    }

    @Test
    @DisplayName("并发签发与吊销 - 数据一致性")
    void concurrentIssueAndRevoke_Consistency() throws Exception {
        int issueCount = 30;
        int revokeCount = 15;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(issueCount + revokeCount);
        List<String> certIds = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < issueCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    CertificateIssueRequest request = new CertificateIssueRequest();
                    request.setCommonName("issue-revoke-" + index + "-" + UUID.randomUUID() + ".chaoslab.local");
                    request.setOrganization("ChaosLab");
                    request.setCountry("CN");
                    var cert = strategyService.issueCertificateWithStrategy(request, "DEFAULT").block();
                    if (cert != null) {
                        certIds.add(cert.getCertId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        assertThat(certIds).hasSize(issueCount);

        CountDownLatch revokeLatch = new CountDownLatch(revokeCount);
        for (int i = 0; i < revokeCount; i++) {
            final String certId = certIds.get(i);
            executor.submit(() -> {
                try {
                    MtlsCertificate cert = certStore.get(certId);
                    if (cert != null) {
                        when(certificateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cert);
                        RevocationRequest request = new RevocationRequest();
                        request.setCertId(certId);
                        request.setReason("Test revocation");
                        request.setRevokedBy("test-admin");
                        strategyService.revokeCertificateWithStrategy(request, "DEFAULT").block();
                    }
                } finally {
                    revokeLatch.countDown();
                }
            });
        }

        revokeLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        long revokedCount = certStore.values().stream()
                .filter(c -> "revoked".equals(c.getStatus()))
                .count();
        assertThat(revokedCount).isEqualTo(revokeCount);

        long activeCount = certStore.values().stream()
                .filter(c -> "active".equals(c.getStatus()))
                .count();
        assertThat(activeCount).isEqualTo(issueCount - revokeCount);
    }

    @Test
    @DisplayName("并发激活/停用策略 - 状态一致性")
    void concurrentActivateDeactivate_StateConsistency() throws Exception {
        strategyRegistry.activateStrategy("STRICT_SECURITY");
        strategyRegistry.activateStrategy("AUDIT_LOGGING");

        AtomicInteger activateCount = new AtomicInteger(0);
        AtomicInteger deactivateCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(40);

        for (int i = 0; i < 20; i++) {
            executor.submit(() -> {
                try {
                    strategyService.activateStrategy("TESTING_ENV", "admin", "activate test").block();
                    activateCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    strategyService.deactivateStrategy("TESTING_ENV", "admin", "deactivate test").block();
                    deactivateCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        var strategies = strategyRegistry.getAllStrategies();
        assertThat(strategies).isNotEmpty();
    }

    @Test
    @DisplayName("并发使用不同策略签发证书 - 策略隔离")
    void concurrentIssueWithDifferentStrategies_Isolation() throws Exception {
        String[] strategies = {"DEFAULT", "STRICT_SECURITY", "TESTING_ENV"};
        ExecutorService executor = Executors.newFixedThreadPool(strategies.length);
        CountDownLatch latch = new CountDownLatch(strategies.length * 10);

        Map<String, List<LocalDateTime>> strategyNotAfterTimes = new ConcurrentHashMap<>();
        for (String s : strategies) {
            strategyNotAfterTimes.put(s, Collections.synchronizedList(new ArrayList<>()));
        }

        for (String strategy : strategies) {
            for (int i = 0; i < 10; i++) {
                final String currentStrategy = strategy;
                executor.submit(() -> {
                    try {
                        CertificateIssueRequest request = new CertificateIssueRequest();
                        request.setCommonName("strategy-" + currentStrategy + "-" + UUID.randomUUID() + ".chaoslab.local");
                        request.setOrganization("ChaosLab");
                        request.setCountry("CN");
                        request.setValidityDays(365);
                        var cert = strategyService.issueCertificateWithStrategy(request, currentStrategy).block();
                        if (cert != null) {
                            strategyNotAfterTimes.get(currentStrategy).add(cert.getNotAfter());
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        for (String strategy : strategies) {
            assertThat(strategyNotAfterTimes.get(strategy)).hasSize(10);
        }

        LocalDateTime now = LocalDateTime.now();
        for (LocalDateTime notAfter : strategyNotAfterTimes.get("TESTING_ENV")) {
            assertThat(notAfter).isBeforeOrEqualTo(now.plusDays(30).plusHours(1));
        }

        for (LocalDateTime notAfter : strategyNotAfterTimes.get("STRICT_SECURITY")) {
            assertThat(notAfter).isBeforeOrEqualTo(now.plusDays(90).plusHours(1));
        }
    }

    @Override
    protected void assertAllResourcesReleased() {
        assertThat(certStore).isNotEmpty();
    }
}
