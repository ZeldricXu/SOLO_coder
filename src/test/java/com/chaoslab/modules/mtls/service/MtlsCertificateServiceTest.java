package com.chaoslab.modules.mtls.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.ConcurrentTestBase;
import com.chaoslab.common.TestDataFactory;
import com.chaoslab.entity.MtlsCertificate;
import com.chaoslab.entity.MtlsRevocationList;
import com.chaoslab.entity.MtlsRotationPolicy;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.MtlsCertificateMapper;
import com.chaoslab.mapper.MtlsRevocationListMapper;
import com.chaoslab.mapper.MtlsRotationPolicyMapper;
import com.chaoslab.modules.mtls.dto.CertificateIssueRequest;
import com.chaoslab.modules.mtls.dto.CertificateResponse;
import com.chaoslab.modules.mtls.dto.RevocationRequest;
import com.chaoslab.modules.mtls.dto.RotationPolicyCreateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("MtlsCertificateService 单元测试")
@Execution(ExecutionMode.SAME_THREAD)
class MtlsCertificateServiceTest extends ConcurrentTestBase {

    @Mock
    private MtlsCertificateMapper certificateMapper;

    @Mock
    private MtlsRotationPolicyMapper rotationPolicyMapper;

    @Mock
    private MtlsRevocationListMapper revocationListMapper;

    @InjectMocks
    private MtlsCertificateService certificateService;

    private final Map<String, MtlsCertificate> certStore = new ConcurrentHashMap<>();
    private final Map<String, MtlsRotationPolicy> policyStore = new ConcurrentHashMap<>();
    private final Map<String, MtlsRevocationList> revocationStore = new ConcurrentHashMap<>();

    private final AtomicInteger certInsertCount = new AtomicInteger(0);
    private final AtomicInteger policyInsertCount = new AtomicInteger(0);
    private final AtomicInteger revocationInsertCount = new AtomicInteger(0);
    private final AtomicInteger certUpdateCount = new AtomicInteger(0);

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        certStore.clear();
        policyStore.clear();
        revocationStore.clear();
        certInsertCount.set(0);
        policyInsertCount.set(0);
        revocationInsertCount.set(0);
        certUpdateCount.set(0);
        setupMockBehaviors();
    }

    @Override
    protected void assertAllResourcesReleased() {
        assertThat(certStore).isEmpty();
        assertThat(policyStore).isEmpty();
        assertThat(revocationStore).isEmpty();
        assertThat(certInsertCount.get()).isEqualTo(0);
        assertThat(policyInsertCount.get()).isEqualTo(0);
        assertThat(revocationInsertCount.get()).isEqualTo(0);
        assertThat(certUpdateCount.get()).isEqualTo(0);
    }

    private void setupMockBehaviors() {
        when(rotationPolicyMapper.insert(any(MtlsRotationPolicy.class))).thenAnswer(invocation -> {
            MtlsRotationPolicy policy = invocation.getArgument(0);
            policyStore.put(policy.getPolicyId(), policy);
            policyInsertCount.incrementAndGet();
            return 1;
        });

        when(rotationPolicyMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            LambdaQueryWrapper<MtlsRotationPolicy> wrapper = invocation.getArgument(0);
            return policyStore.values().stream().findFirst().orElse(null);
        });

        when(rotationPolicyMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return new ArrayList<>(policyStore.values());
        });

        when(certificateMapper.insert(any(MtlsCertificate.class))).thenAnswer(invocation -> {
            MtlsCertificate cert = invocation.getArgument(0);
            certStore.put(cert.getCertId(), cert);
            certInsertCount.incrementAndGet();
            return 1;
        });

        when(certificateMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            LambdaQueryWrapper<MtlsCertificate> wrapper = invocation.getArgument(0);
            return certStore.values().stream().findFirst().orElse(null);
        });

        when(certificateMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            LambdaQueryWrapper<MtlsCertificate> wrapper = invocation.getArgument(0);
            return new ArrayList<>(certStore.values());
        });

        when(certificateMapper.updateById(any(MtlsCertificate.class))).thenAnswer(invocation -> {
            MtlsCertificate cert = invocation.getArgument(0);
            certStore.put(cert.getCertId(), cert);
            certUpdateCount.incrementAndGet();
            return 1;
        });

        when(revocationListMapper.insert(any(MtlsRevocationList.class))).thenAnswer(invocation -> {
            MtlsRevocationList revocation = invocation.getArgument(0);
            revocationStore.put(revocation.getRevocationId(), revocation);
            revocationInsertCount.incrementAndGet();
            return 1;
        });

        when(revocationListMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return revocationStore.values().stream().findFirst().orElse(null);
        });

        when(revocationListMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return new ArrayList<>(revocationStore.values());
        });

        when(revocationListMapper.selectCount(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return (long) revocationStore.size();
        });
    }

    private void releaseResources() {
        certStore.clear();
        policyStore.clear();
        revocationStore.clear();
        certInsertCount.set(0);
        policyInsertCount.set(0);
        revocationInsertCount.set(0);
        certUpdateCount.set(0);
    }

    // ==================== 正常路径测试 ====================

    @Nested
    @DisplayName("正常路径测试")
    class NormalPathTests {

        @Test
        @DisplayName("创建轮转策略 - 成功")
        void createRotationPolicy_Success() {
            RotationPolicyCreateRequest request = TestDataFactory.createRotationPolicyRequest();

            Mono<MtlsRotationPolicy> result = certificateService.createRotationPolicy(request);

            StepVerifier.create(result)
                    .expectNextMatches(policy -> {
                        assertThat(policy.getPolicyId()).isNotNull().startsWith("rp-");
                        assertThat(policy.getName()).isEqualTo(request.getName());
                        assertThat(policy.getValidityDays()).isEqualTo(request.getValidityDays());
                        assertThat(policy.getRotationDays()).isEqualTo(request.getRotationDays());
                        assertThat(policy.getAutoRotate()).isEqualTo(request.getAutoRotate());
                        assertThat(policy.getKeyAlgorithm()).isEqualTo(request.getKeyAlgorithm());
                        assertThat(policy.getKeySize()).isEqualTo(request.getKeySize());
                        assertThat(policy.getEnabled()).isTrue();
                        assertThat(policyStore).containsKey(policy.getPolicyId());
                        return true;
                    })
                    .verifyComplete();

            verify(rotationPolicyMapper, times(1)).insert(any(MtlsRotationPolicy.class));
            assertThat(policyInsertCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("查询轮转策略列表 - 成功")
        void listRotationPolicies_Success() {
            for (int i = 0; i < 3; i++) {
                MtlsRotationPolicy policy = TestDataFactory.createMtlsRotationPolicy();
                policyStore.put(policy.getPolicyId(), policy);
            }

            Mono<List<MtlsRotationPolicy>> result = certificateService.listRotationPolicies();

            StepVerifier.create(result)
                    .expectNextMatches(policies -> {
                        assertThat(policies).hasSize(3);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("签发证书 - 成功（不使用轮转策略）")
        void issueCertificate_Success_WithoutPolicy() {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            request.setRotationPolicyId(null);

            Mono<CertificateResponse> result = certificateService.issueCertificate(request);

            StepVerifier.create(result)
                    .expectNextMatches(cert -> {
                        assertThat(cert.getCertId()).isNotNull().startsWith("cert-");
                        assertThat(cert.getCommonName()).isEqualTo(request.getCommonName());
                        assertThat(cert.getStatus()).isEqualTo("active");
                        assertThat(cert.getCertificatePem()).isNotNull().contains("BEGIN CERTIFICATE");
                        assertThat(cert.getPrivateKeyPem()).isNotNull().contains("BEGIN");
                        assertThat(cert.getSerialNumber()).isNotNull();
                        assertThat(cert.getNotBefore()).isNotNull();
                        assertThat(cert.getNotAfter()).isNotNull();
                        assertThat(cert.getNotAfter()).isAfter(cert.getNotBefore());
                        assertThat(certStore).containsKey(cert.getCertId());
                        return true;
                    })
                    .verifyComplete();

            assertThat(certInsertCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("签发证书 - 成功（使用轮转策略）")
        void issueCertificate_Success_WithPolicy() {
            MtlsRotationPolicy policy = TestDataFactory.createMtlsRotationPolicy();
            policyStore.put(policy.getPolicyId(), policy);

            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            request.setRotationPolicyId(policy.getPolicyId());

            Mono<CertificateResponse> result = certificateService.issueCertificate(request);

            StepVerifier.create(result)
                    .expectNextMatches(cert -> {
                        assertThat(cert.getCommonName()).isEqualTo(request.getCommonName());
                        assertThat(cert.getRotationPolicyId()).isEqualTo(policy.getPolicyId());
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("获取证书 - 成功")
        void getCertificate_Success() {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            CertificateResponse response = certificateService.issueCertificate(request).block();
            assertNotNull(response);

            Mono<CertificateResponse> result = certificateService.getCertificate(response.getCertId());

            StepVerifier.create(result)
                    .expectNextMatches(cert -> {
                        assertThat(cert.getCertId()).isEqualTo(response.getCertId());
                        assertThat(cert.getCommonName()).isEqualTo(request.getCommonName());
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("查询证书列表 - 无过滤")
        void listCertificates_NoFilter() {
            for (int i = 0; i < 5; i++) {
                CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                certificateService.issueCertificate(request).block();
            }

            Mono<List<CertificateResponse>> result = certificateService.listCertificates(null, null);

            StepVerifier.create(result)
                    .expectNextMatches(certs -> {
                        assertThat(certs).hasSize(5);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("查询证书列表 - 按状态过滤")
        void listCertificates_ByStatus() {
            for (int i = 0; i < 3; i++) {
                CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                certificateService.issueCertificate(request).block();
            }

            Mono<List<CertificateResponse>> result = certificateService.listCertificates("active", null);

            StepVerifier.create(result)
                    .expectNextMatches(certs -> {
                        assertThat(certs).hasSize(3);
                        assertThat(certs).allMatch(c -> "active".equals(c.getStatus()));
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("查询证书列表 - 按CommonName模糊查询")
        void listCertificates_ByCommonName() {
            CertificateIssueRequest req1 = TestDataFactory.createCertificateIssueRequest();
            req1.setCommonName("service-api.example.com");
            certificateService.issueCertificate(req1).block();

            CertificateIssueRequest req2 = TestDataFactory.createCertificateIssueRequest();
            req2.setCommonName("service-web.example.com");
            certificateService.issueCertificate(req2).block();

            CertificateIssueRequest req3 = TestDataFactory.createCertificateIssueRequest();
            req3.setCommonName("database.internal");
            certificateService.issueCertificate(req3).block();

            Mono<List<CertificateResponse>> result = certificateService.listCertificates(null, "service");

            StepVerifier.create(result)
                    .expectNextMatches(certs -> {
                        assertThat(certs).hasSize(2);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("吊销证书 - 成功")
        void revokeCertificate_Success() {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            CertificateResponse response = certificateService.issueCertificate(request).block();
            assertNotNull(response);

            RevocationRequest revocationRequest = TestDataFactory.createRevocationRequest(response.getCertId());

            Mono<MtlsRevocationList> result = certificateService.revokeCertificate(revocationRequest);

            StepVerifier.create(result)
                    .expectNextMatches(revocation -> {
                        assertThat(revocation.getRevocationId()).isNotNull().startsWith("rev-");
                        assertThat(revocation.getCertId()).isEqualTo(response.getCertId());
                        assertThat(revocation.getReason()).isEqualTo(revocationRequest.getReason());
                        assertThat(revocation.getRevokedBy()).isEqualTo(revocationRequest.getRevokedBy());
                        assertThat(revocation.getRevokedAt()).isNotNull();
                        assertThat(revocation.getCrlNumber()).isGreaterThan(0);
                        assertThat(revocationStore).containsKey(revocation.getRevocationId());
                        assertThat(certStore.get(response.getCertId()).getStatus()).isEqualTo("revoked");
                        return true;
                    })
                    .verifyComplete();

            assertThat(revocationInsertCount.get()).isEqualTo(1);
            assertThat(certUpdateCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("获取吊销列表 - 成功")
        void getRevocationList_Success() {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            CertificateResponse response = certificateService.issueCertificate(request).block();
            assertNotNull(response);

            RevocationRequest revocationRequest = TestDataFactory.createRevocationRequest(response.getCertId());
            certificateService.revokeCertificate(revocationRequest).block();

            Mono<List<MtlsRevocationList>> result = certificateService.getRevocationList();

            StepVerifier.create(result)
                    .expectNextMatches(revocations -> {
                        assertThat(revocations).hasSize(1);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("获取CRL - 成功")
        void getCrl_Success() {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            CertificateResponse response = certificateService.issueCertificate(request).block();
            assertNotNull(response);

            RevocationRequest revocationRequest = TestDataFactory.createRevocationRequest(response.getCertId());
            certificateService.revokeCertificate(revocationRequest).block();

            Mono<String> result = certificateService.getCrl();

            StepVerifier.create(result)
                    .expectNextMatches(crl -> {
                        assertThat(crl).contains("-----BEGIN X509 CRL-----");
                        assertThat(crl).contains("-----END X509 CRL-----");
                        assertThat(crl).contains("Issuer: CN=ChaosLab CA");
                        assertThat(crl).contains("Serial Number:");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("获取空CRL - 成功")
        void getCrl_Empty() {
            Mono<String> result = certificateService.getCrl();

            StepVerifier.create(result)
                    .expectNextMatches(crl -> {
                        assertThat(crl).contains("-----BEGIN X509 CRL-----");
                        assertThat(crl).contains("-----END X509 CRL-----");
                        assertThat(crl).contains("Revoked Certificates:");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("轮转即将过期证书 - 成功")
        void rotateExpiringCertificates_Success() {
            MtlsRotationPolicy policy = TestDataFactory.createMtlsRotationPolicy();
            policyStore.put(policy.getPolicyId(), policy);

            for (int i = 0; i < 3; i++) {
                CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                request.setRotationPolicyId(policy.getPolicyId());
                CertificateResponse cert = certificateService.issueCertificate(request).block();
                assertNotNull(cert);

                MtlsCertificate mtlsCert = certStore.get(cert.getCertId());
                mtlsCert.setNotAfter(LocalDateTime.now().plusDays(10));
                certStore.put(cert.getCertId(), mtlsCert);
            }

            Flux<CertificateResponse> result = certificateService.rotateExpiringCertificates();

            StepVerifier.create(result)
                    .expectNextCount(3)
                    .verifyComplete();

            assertThat(certInsertCount.get()).isEqualTo(6);
            assertThat(certUpdateCount.get()).isEqualTo(3);

            List<MtlsCertificate> rotated = certStore.values().stream()
                    .filter(c -> "rotated".equals(c.getStatus()))
                    .toList();
            assertThat(rotated).hasSize(3);

            List<MtlsCertificate> active = certStore.values().stream()
                    .filter(c -> "active".equals(c.getStatus()))
                    .toList();
            assertThat(active).hasSize(3);
        }

        @Test
        @DisplayName("检查证书是否被吊销 - 已吊销")
        void isCertificateRevoked_True() {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            CertificateResponse response = certificateService.issueCertificate(request).block();
            assertNotNull(response);

            RevocationRequest revocationRequest = TestDataFactory.createRevocationRequest(response.getCertId());
            certificateService.revokeCertificate(revocationRequest).block();

            boolean result = certificateService.isCertificateRevoked(response.getSerialNumber());

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("检查证书是否被吊销 - 未吊销")
        void isCertificateRevoked_False() {
            boolean result = certificateService.isCertificateRevoked("non-existent-serial");

            assertThat(result).isFalse();
        }
    }

    // ==================== 异常路径测试 ====================

    @Nested
    @DisplayName("异常路径测试")
    class ExceptionPathTests {

        @Test
        @DisplayName("获取证书 - 不存在")
        void getCertificate_NotFound() {
            when(certificateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            Mono<CertificateResponse> result = certificateService.getCertificate("non-existent");

            StepVerifier.create(result)
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable)
                                .isInstanceOf(BusinessException.class)
                                .hasMessageContaining("不存在");
                        BusinessException be = (BusinessException) throwable;
                        assertThat(be.getCode()).isEqualTo(404);
                    })
                    .verify();
        }

        @Test
        @DisplayName("吊销证书 - 证书不存在")
        void revokeCertificate_NotFound() {
            when(certificateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            RevocationRequest request = TestDataFactory.createRevocationRequest("non-existent");

            Mono<MtlsRevocationList> result = certificateService.revokeCertificate(request);

            StepVerifier.create(result)
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(BusinessException.class);
                        assertThat(((BusinessException) throwable).getCode()).isEqualTo(404);
                    })
                    .verify();

            assertThat(revocationStore).isEmpty();
        }

        @Test
        @DisplayName("吊销证书 - 证书已被吊销")
        void revokeCertificate_AlreadyRevoked() {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            CertificateResponse response = certificateService.issueCertificate(request).block();
            assertNotNull(response);

            RevocationRequest revRequest = TestDataFactory.createRevocationRequest(response.getCertId());
            certificateService.revokeCertificate(revRequest).block();

            when(certificateMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(certStore.get(response.getCertId()));

            Mono<MtlsRevocationList> result = certificateService.revokeCertificate(revRequest);

            StepVerifier.create(result)
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable)
                                .isInstanceOf(BusinessException.class)
                                .hasMessageContaining("已被吊销");
                        BusinessException be = (BusinessException) throwable;
                        assertThat(be.getCode()).isEqualTo(422);
                    })
                    .verify();

            assertThat(revocationInsertCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("签发证书 - 轮转策略不存在")
        void issueCertificate_PolicyNotFound() {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            request.setRotationPolicyId("non-existent");
            when(rotationPolicyMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            Mono<CertificateResponse> result = certificateService.issueCertificate(request);

            StepVerifier.create(result)
                    .expectNextMatches(cert -> {
                        assertThat(cert.getStatus()).isEqualTo("active");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("轮转证书 - 没有需要轮转的证书")
        void rotateExpiringCertificates_NoneExpiring() {
            MtlsRotationPolicy policy = TestDataFactory.createMtlsRotationPolicy();
            policyStore.put(policy.getPolicyId(), policy);

            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            request.setRotationPolicyId(policy.getPolicyId());
            certificateService.issueCertificate(request).block();

            Flux<CertificateResponse> result = certificateService.rotateExpiringCertificates();

            StepVerifier.create(result)
                    .verifyComplete();
        }

        @Test
        @DisplayName("查询证书列表 - 空列表")
        void listCertificates_Empty() {
            Mono<List<CertificateResponse>> result = certificateService.listCertificates(null, null);

            StepVerifier.create(result)
                    .expectNextMatches(certs -> {
                        assertThat(certs).isEmpty();
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("查询证书列表 - 状态过滤无匹配")
        void listCertificates_StatusNoMatch() {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            certificateService.issueCertificate(request).block();

            Mono<List<CertificateResponse>> result = certificateService.listCertificates("revoked", null);

            StepVerifier.create(result)
                    .expectNextMatches(certs -> {
                        assertThat(certs).isEmpty();
                        return true;
                    })
                    .verifyComplete();
        }
    }

    // ==================== 并发线程安全性测试 ====================

    @Nested
    @DisplayName("并发线程安全性测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发创建轮转策略 - 线程安全")
        void createRotationPolicy_Concurrent() throws Exception {
            assertConcurrentSafety(
                    () -> {
                        RotationPolicyCreateRequest request = TestDataFactory.createRotationPolicyRequest();
                        return certificateService.createRotationPolicy(request).block();
                    },
                    DEFAULT_THREAD_COUNT,
                    DEFAULT_ITERATIONS
            );

            assertThat(policyInsertCount.get()).isPositive();
        }

        @Test
        @DisplayName("并发创建轮转策略 - ID不重复")
        void createRotationPolicy_Concurrent_NoDuplicateIds() throws Exception {
            Set<String> createdIds = Collections.synchronizedSet(new HashSet<>());

            assertConcurrentCorrectness(
                    () -> {
                        RotationPolicyCreateRequest request = TestDataFactory.createRotationPolicyRequest();
                        return certificateService.createRotationPolicy(request).block();
                    },
                    policy -> {
                        assertNotNull(policy);
                        assertNotNull(policy.getPolicyId());
                        assertFalse(createdIds.contains(policy.getPolicyId()),
                                "Duplicate policy ID: " + policy.getPolicyId());
                        createdIds.add(policy.getPolicyId());
                    },
                    DEFAULT_THREAD_COUNT,
                    DEFAULT_ITERATIONS
            );

            assertThat(createdIds).hasSize(DEFAULT_THREAD_COUNT * DEFAULT_ITERATIONS);
        }

        @Test
        @DisplayName("并发签发证书 - 线程安全")
        void issueCertificate_Concurrent() throws Exception {
            assertConcurrentSafety(
                    () -> {
                        CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                        return certificateService.issueCertificate(request).block();
                    },
                    5,
                    20
            );

            assertThat(certInsertCount.get()).isPositive();
        }

        @Test
        @DisplayName("并发签发证书 - 序列号唯一")
        void issueCertificate_Concurrent_UniqueSerialNumbers() throws Exception {
            Set<String> serialNumbers = Collections.synchronizedSet(new HashSet<>());

            assertConcurrentCorrectness(
                    () -> {
                        CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                        return certificateService.issueCertificate(request).block();
                    },
                    cert -> {
                        assertNotNull(cert);
                        assertNotNull(cert.getSerialNumber());
                        assertFalse(serialNumbers.contains(cert.getSerialNumber()),
                                "Duplicate serial number: " + cert.getSerialNumber());
                        serialNumbers.add(cert.getSerialNumber());
                        assertThat(cert.getCertificatePem()).isNotNull();
                        assertThat(cert.getPrivateKeyPem()).isNotNull();
                    },
                    5,
                    10
            );

            assertThat(serialNumbers).hasSize(50);
        }

        @Test
        @DisplayName("并发吊销证书 - 线程安全")
        void revokeCertificate_Concurrent() throws Exception {
            MtlsRotationPolicy policy = TestDataFactory.createMtlsRotationPolicy();
            policyStore.put(policy.getPolicyId(), policy);

            Queue<String> certIds = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < 50; i++) {
                CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                CertificateResponse cert = certificateService.issueCertificate(request).block();
                assertNotNull(cert);
                certIds.offer(cert.getCertId());
            }

            int initialCertCount = certInsertCount.get();

            when(certificateMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
                return certStore.values().stream()
                        .filter(c -> "active".equals(c.getStatus()))
                        .findFirst()
                        .orElse(null);
            });

            assertConcurrentSafety(
                    () -> {
                        String certId = certIds.poll();
                        if (certId == null) {
                            return null;
                        }
                        RevocationRequest request = TestDataFactory.createRevocationRequest(certId);
                        return certificateService.revokeCertificate(request).block();
                    },
                    10,
                    5
            );

            assertThat(revocationInsertCount.get()).isEqualTo(50);
            assertThat(certUpdateCount.get()).isEqualTo(50);
        }

        @Test
        @DisplayName("并发签发和查询 - 无数据竞争")
        void concurrentIssueAndQuery_NoDataRace() throws Exception {
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(8);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(8);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            List<String> issuedCertIds = Collections.synchronizedList(new ArrayList<>());

            try {
                for (int i = 0; i < 4; i++) {
                    executor.submit(() -> {
                        try {
                            latch.await();
                            for (int j = 0; j < 25; j++) {
                                CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                                CertificateResponse cert = certificateService.issueCertificate(request).block();
                                if (cert != null) {
                                    issuedCertIds.add(cert.getCertId());
                                }
                            }
                        } catch (Throwable t) {
                            errors.add(t);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                for (int i = 0; i < 4; i++) {
                    executor.submit(() -> {
                        try {
                            latch.await();
                            for (int j = 0; j < 25; j++) {
                                if (!issuedCertIds.isEmpty()) {
                                    String randomId = issuedCertIds.get(
                                            new Random().nextInt(issuedCertIds.size()));
                                    certificateService.getCertificate(randomId)
                                            .onErrorResume(e -> Mono.empty())
                                            .block();
                                }
                                sleep(5);
                            }
                        } catch (Throwable t) {
                            errors.add(t);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                latch.countDown();
                assertTrue(doneLatch.await(60, java.util.concurrent.TimeUnit.SECONDS));

                assertThat(errors).isEmpty();
                assertThat(issuedCertIds).hasSize(100);

            } finally {
                executor.shutdownNow();
            }
        }
    }

    // ==================== 资源释放闭环测试 ====================

    @Nested
    @DisplayName("资源完整释放闭环测试")
    class ResourceReleaseTests {

        @Test
        @DisplayName("签发后清理 - 资源完整释放")
        void issueAndCleanup_ResourcesReleased() throws Exception {
            Runnable acquire = () -> {
                CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                CertificateResponse cert = certificateService.issueCertificate(request).block();
                assertNotNull(cert);
            };

            assertResourceRelease(acquire, this::releaseResources, 50);
        }

        @Test
        @DisplayName("签发后清理 - 并发资源释放")
        void issueAndCleanup_ConcurrentResourcesReleased() throws Exception {
            Runnable acquire = () -> {
                CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                CertificateResponse cert = certificateService.issueCertificate(request).block();
                assertNotNull(cert);
            };

            assertResourceReleaseConcurrent(acquire, this::releaseResources, 10, 20);
        }

        @Test
        @DisplayName("吊销后清理 - 资源完整释放")
        void revokeAndCleanup_ResourcesReleased() throws Exception {
            Runnable acquire = () -> {
                CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                CertificateResponse cert = certificateService.issueCertificate(request).block();
                assertNotNull(cert);

                RevocationRequest revRequest = TestDataFactory.createRevocationRequest(cert.getCertId());
                MtlsRevocationList revocation = certificateService.revokeCertificate(revRequest).block();
                assertNotNull(revocation);
            };

            assertResourceRelease(acquire, this::releaseResources, 30);
        }

        @Test
        @DisplayName("异常场景下 - 资源仍然释放")
        void exceptionScenario_ResourcesReleased() throws Exception {
            AtomicInteger attemptCount = new AtomicInteger(0);

            Runnable acquire = () -> {
                int attempt = attemptCount.incrementAndGet();
                if (attempt % 3 == 0) {
                    try {
                        certificateService.getCertificate("non-existent").block();
                        fail("Should have thrown exception");
                    } catch (BusinessException e) {
                        // Expected
                    }
                } else if (attempt % 3 == 1) {
                    try {
                        RevocationRequest request = TestDataFactory.createRevocationRequest("non-existent");
                        certificateService.revokeCertificate(request).block();
                        fail("Should have thrown exception");
                    } catch (BusinessException e) {
                        // Expected
                    }
                } else {
                    CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                    CertificateResponse cert = certificateService.issueCertificate(request).block();
                    assertNotNull(cert);
                }
            };

            assertResourceRelease(acquire, this::releaseResources, 60);
        }

        @Test
        @DisplayName("完整生命周期 - 签发→吊销→清理")
        void fullLifecycle_IssueRevokeCleanup() throws Exception {
            Runnable acquire = () -> {
                RotationPolicyCreateRequest policyRequest = TestDataFactory.createRotationPolicyRequest();
                MtlsRotationPolicy policy = certificateService.createRotationPolicy(policyRequest).block();
                assertNotNull(policy);

                CertificateIssueRequest certRequest = TestDataFactory.createCertificateIssueRequest();
                certRequest.setRotationPolicyId(policy.getPolicyId());
                CertificateResponse cert = certificateService.issueCertificate(certRequest).block();
                assertNotNull(cert);

                RevocationRequest revRequest = TestDataFactory.createRevocationRequest(cert.getCertId());
                MtlsRevocationList revocation = certificateService.revokeCertificate(revRequest).block();
                assertNotNull(revocation);

                boolean isRevoked = certificateService.isCertificateRevoked(cert.getSerialNumber());
                assertTrue(isRevoked);
            };

            assertResourceRelease(acquire, this::releaseResources, 20);
        }

        @Test
        @DisplayName("证书生成异常回滚 - 资源释放完整")
        void certGenerationRollback_ResourcesReleased() {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();

            for (int i = 0; i < 10; i++) {
                CertificateResponse cert = certificateService.issueCertificate(request).block();
                assertNotNull(cert);
                assertThat(certStore).hasSize(i + 1);
            }

            releaseResources();
            assertAllResourcesReleased();
        }

        @Test
        @DisplayName("CRL编号并发递增 - 线程安全")
        void crlNumberConcurrentIncrement_ThreadSafe() throws Exception {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            CertificateResponse cert = certificateService.issueCertificate(request).block();
            assertNotNull(cert);

            certStore.values().forEach(c -> {
                when(certificateMapper.selectOne(any(LambdaQueryWrapper.class)))
                        .thenReturn(c);
            });

            int threadCount = 10;
            int iterations = 10;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            List<Integer> crlNumbers = Collections.synchronizedList(new ArrayList<>());

            try {
                for (int i = 0; i < threadCount; i++) {
                    final int threadIdx = i;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (int j = 0; j < iterations; j++) {
                                try {
                                    CertificateIssueRequest newRequest = TestDataFactory.createCertificateIssueRequest();
                                    CertificateResponse newCert = certificateService.issueCertificate(newRequest).block();
                                    assertNotNull(newCert);

                                    RevocationRequest revRequest = TestDataFactory.createRevocationRequest(newCert.getCertId());
                                    MtlsRevocationList revocation = certificateService.revokeCertificate(revRequest).block();
                                    if (revocation != null) {
                                        crlNumbers.add(revocation.getCrlNumber());
                                    }
                                } catch (Exception e) {
                                    // Skip failed attempts
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            endLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                endLatch.await(60, TimeUnit.SECONDS);

                assertThat(crlNumbers).isNotEmpty();

                Collections.sort(crlNumbers);
                for (int i = 1; i < crlNumbers.size(); i++) {
                    assertThat(crlNumbers.get(i)).isGreaterThan(crlNumbers.get(i - 1));
                }

            } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }

            releaseResources();
            assertAllResourcesReleased();
        }
    }

    // ==================== 边界条件测试 ====================

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTests {

        @Test
        @DisplayName("签发证书 - 最大有效期")
        void issueCertificate_MaxValidity() {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            request.setValidityDays(3650);

            Mono<CertificateResponse> result = certificateService.issueCertificate(request);

            StepVerifier.create(result)
                    .expectNextMatches(cert -> {
                        assertThat(cert.getNotAfter()).isAfter(cert.getNotBefore());
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("签发证书 - 最小有效期")
        void issueCertificate_MinValidity() {
            CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
            request.setValidityDays(1);

            Mono<CertificateResponse> result = certificateService.issueCertificate(request);

            StepVerifier.create(result)
                    .expectNextMatches(cert -> {
                        assertThat(cert.getNotAfter()).isAfter(cert.getNotBefore());
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("查询证书列表 - 多条件组合过滤")
        void listCertificates_MultiFilter() {
            CertificateIssueRequest req1 = TestDataFactory.createCertificateIssueRequest();
            req1.setCommonName("api.service.com");
            CertificateResponse cert1 = certificateService.issueCertificate(req1).block();
            assertNotNull(cert1);

            CertificateIssueRequest req2 = TestDataFactory.createCertificateIssueRequest();
            req2.setCommonName("web.service.com");
            certificateService.issueCertificate(req2).block();

            CertificateIssueRequest req3 = TestDataFactory.createCertificateIssueRequest();
            req3.setCommonName("api.other.com");
            certificateService.issueCertificate(req3).block();

            RevocationRequest revRequest = TestDataFactory.createRevocationRequest(cert1.getCertId());
            certificateService.revokeCertificate(revRequest).block();

            Mono<List<CertificateResponse>> result = certificateService.listCertificates("active", "api");

            StepVerifier.create(result)
                    .expectNextMatches(certs -> {
                        assertThat(certs).hasSize(1);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("CRL格式验证 - 包含所有吊销信息")
        void getCrl_FormatValidation() {
            for (int i = 0; i < 3; i++) {
                CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                CertificateResponse cert = certificateService.issueCertificate(request).block();
                assertNotNull(cert);

                RevocationRequest revRequest = TestDataFactory.createRevocationRequest(cert.getCertId());
                certificateService.revokeCertificate(revRequest).block();
            }

            String crl = certificateService.getCrl().block();
            assertNotNull(crl);

            for (MtlsRevocationList rev : revocationStore.values()) {
                assertThat(crl).contains(rev.getSerialNumber());
            }
        }

        @Test
        @DisplayName("轮转证书 - 部分过期部分有效")
        void rotateExpiringCertificates_PartialExpiring() {
            MtlsRotationPolicy policy = TestDataFactory.createMtlsRotationPolicy();
            policyStore.put(policy.getPolicyId(), policy);

            for (int i = 0; i < 5; i++) {
                CertificateIssueRequest request = TestDataFactory.createCertificateIssueRequest();
                request.setRotationPolicyId(policy.getPolicyId());
                CertificateResponse cert = certificateService.issueCertificate(request).block();
                assertNotNull(cert);

                if (i < 2) {
                    MtlsCertificate mtlsCert = certStore.get(cert.getCertId());
                    mtlsCert.setNotAfter(LocalDateTime.now().plusDays(10));
                    certStore.put(cert.getCertId(), mtlsCert);
                }
            }

            Flux<CertificateResponse> result = certificateService.rotateExpiringCertificates();

            StepVerifier.create(result)
                    .expectNextCount(2)
                    .verifyComplete();

            assertThat(certInsertCount.get()).isEqualTo(7);
        }
    }

    @AfterEach
    @Override
    protected void tearDown() throws Exception {
        releaseResources();
        assertAllResourcesReleased();
        super.tearDown();
    }
}
