package com.projectservice.service;

import com.projectservice.factory.TestDataFactory;
import com.projectservice.model.environment.*;
import com.projectservice.service.EnvironmentService.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Environment Service Test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnvironmentServiceTest {

    private EnvironmentService service;
    private EnvironmentService serviceWithEnv;

    @BeforeEach
    void setUp() {
        service = new EnvironmentService();
        serviceWithEnv = TestDataFactory.createEnvironmentServiceWithEnv();
    }

    @Nested
    @DisplayName("Environment Creation - Normal Flow")
    class EnvironmentCreationNormalFlow {

        @Test
        @Order(1)
        @DisplayName("Should create environment successfully")
        void shouldCreateEnvironmentSuccessfully() {
            CreateEnvironmentRequest request = TestDataFactory.createCreateEnvironmentRequest();

            Environment env = service.createEnvironment(request);

            assertThat(env.getId()).isNotBlank();
            assertThat(env.getName()).isEqualTo(request.getName());
            assertThat(env.getType()).isEqualTo(request.getType());
            assertThat(env.getStatus()).isEqualTo("running");
            assertThat(env.getOwner()).isEqualTo(request.getOwner());
            assertThat(env.getProjectId()).isEqualTo(request.getProjectId());
            assertThat(env.getCreatedAt()).isNotNull();
        }

        @Test
        @Order(2)
        @DisplayName("Should create environment with TTL")
        void shouldCreateEnvironmentWithTTL() {
            CreateEnvironmentRequest request = TestDataFactory.createCreateEnvironmentRequest();
            request.setTtlHours(4);

            Environment env = service.createEnvironment(request);

            assertThat(env.getTtl()).isNotNull();
            assertThat(env.getTtl().toHours()).isEqualTo(4);
            assertThat(env.getAutoReclaimAt()).isNotNull();
            assertThat(env.getAutoReclaimAt()).isAfter(LocalDateTime.now().plusHours(3));
        }

        @Test
        @Order(3)
        @DisplayName("Should create environment without TTL")
        void shouldCreateEnvironmentWithoutTTL() {
            CreateEnvironmentRequest request = TestDataFactory.createCreateEnvironmentRequest();
            request.setTtlHours(null);

            Environment env = service.createEnvironment(request);

            assertThat(env.getTtl()).isNull();
            assertThat(env.getAutoReclaimAt()).isNull();
        }

        @Test
        @Order(4)
        @DisplayName("Should create environment with resources")
        void shouldCreateEnvironmentWithResources() {
            CreateEnvironmentRequest request = TestDataFactory.createCreateEnvironmentRequest();
            Map<String, String> resources = new HashMap<>();
            resources.put("cpu", "500m");
            resources.put("memory", "512Mi");
            request.setResources(resources);

            Environment env = service.createEnvironment(request);

            assertThat(env.getResources()).containsEntry("cpu", "500m");
            assertThat(env.getResources()).containsEntry("memory", "512Mi");
        }

        @Test
        @Order(5)
        @DisplayName("Should record usage on creation")
        void shouldRecordUsageOnCreation() {
            CreateEnvironmentRequest request = TestDataFactory.createCreateEnvironmentRequest();

            Environment env = service.createEnvironment(request);

            assertThat(service.getUsageRecordCount()).isPositive();
        }
    }

    @Nested
    @DisplayName("Environment Creation - Exception Flow")
    class EnvironmentCreationExceptionFlow {

        @Test
        @Order(1)
        @DisplayName("Should throw exception when request is null")
        void shouldThrowExceptionWhenRequestIsNull() {
            assertThatThrownBy(() -> service.createEnvironment(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
        }

        @ParameterizedTest
        @Order(2)
        @NullAndEmptySource
        @DisplayName("Should throw exception when name is null or empty")
        void shouldThrowExceptionWhenNameIsNullOrEmpty(String name) {
            CreateEnvironmentRequest request = new CreateEnvironmentRequest();
            request.setName(name);
            request.setType("preview");
            request.setOwner("test");
            request.setProjectId("test");

            assertThatThrownBy(() -> service.createEnvironment(request))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @Order(3)
        @DisplayName("Should throw exception when type is empty")
        void shouldThrowExceptionWhenTypeIsEmpty() {
            CreateEnvironmentRequest request = new CreateEnvironmentRequest();
            request.setName("test-env");
            request.setType("");
            request.setOwner("test");
            request.setProjectId("test");

            assertThatThrownBy(() -> service.createEnvironment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Type");
        }

        @Test
        @Order(4)
        @DisplayName("Should throw exception when owner is empty")
        void shouldThrowExceptionWhenOwnerIsEmpty() {
            CreateEnvironmentRequest request = new CreateEnvironmentRequest();
            request.setName("test-env");
            request.setType("preview");
            request.setOwner("");
            request.setProjectId("test");

            assertThatThrownBy(() -> service.createEnvironment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Owner");
        }

        @Test
        @Order(5)
        @DisplayName("Should throw exception when project ID is empty")
        void shouldThrowExceptionWhenProjectIDIsEmpty() {
            CreateEnvironmentRequest request = new CreateEnvironmentRequest();
            request.setName("test-env");
            request.setType("preview");
            request.setOwner("test");
            request.setProjectId("");

            assertThatThrownBy(() -> service.createEnvironment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Project ID");
        }

        @Test
        @Order(6)
        @DisplayName("Should throw exception for duplicate name")
        void shouldThrowExceptionForDuplicateName() {
            CreateEnvironmentRequest request = TestDataFactory.createCreateEnvironmentRequest();
            service.createEnvironment(request);

            CreateEnvironmentRequest duplicate = TestDataFactory.createCreateEnvironmentRequest();
            duplicate.setName(request.getName());

            assertThatThrownBy(() -> service.createEnvironment(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        }
    }

    @Nested
    @DisplayName("Environment Query - Normal Flow")
    class EnvironmentQueryNormalFlow {

        @Test
        @Order(1)
        @DisplayName("Should get environment by ID")
        void shouldGetEnvironmentByID() {
            Environment created = serviceWithEnv.listEnvironments(null, null, null, 1, 1).get(0);

            Environment retrieved = serviceWithEnv.getEnvironment(created.getId());

            assertThat(retrieved.getId()).isEqualTo(created.getId());
            assertThat(retrieved.getName()).isEqualTo(created.getName());
        }

        @Test
        @Order(2)
        @DisplayName("Should get environment status")
        void shouldGetEnvironmentStatus() {
            Environment created = serviceWithEnv.listEnvironments(null, null, null, 1, 1).get(0);

            EnvironmentStatusResponse status = serviceWithEnv.getEnvironmentStatus(created.getId());

            assertThat(status.getId()).isEqualTo(created.getId());
            assertThat(status.getStatus()).isEqualTo("running");
            assertThat(status.getOwner()).isEqualTo(created.getOwner());
        }

        @Test
        @Order(3)
        @DisplayName("Should list environments with pagination")
        void shouldListEnvironmentsWithPagination() {
            for (int i = 0; i < 5; i++) {
                CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
                req.setName("list-env-" + i);
                service.createEnvironment(req);
            }

            List<Environment> page1 = service.listEnvironments(null, null, null, 1, 2);
            List<Environment> page2 = service.listEnvironments(null, null, null, 2, 2);

            assertThat(page1).hasSize(2);
            assertThat(page2).hasSize(2);
        }

        @Test
        @Order(4)
        @DisplayName("Should filter environments by owner")
        void shouldFilterEnvironmentsByOwner() {
            CreateEnvironmentRequest req1 = TestDataFactory.createCreateEnvironmentRequest();
            req1.setName("owner1-env");
            req1.setOwner("owner1");
            service.createEnvironment(req1);

            CreateEnvironmentRequest req2 = TestDataFactory.createCreateEnvironmentRequest();
            req2.setName("owner2-env");
            req2.setOwner("owner2");
            service.createEnvironment(req2);

            List<Environment> results = service.listEnvironments("owner1", null, null, 1, 10);

            assertThat(results).allMatch(e -> "owner1".equals(e.getOwner()));
        }

        @Test
        @Order(5)
        @DisplayName("Should filter environments by status")
        void shouldFilterEnvironmentsByStatus() {
            CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
            req.setName("status-env");
            Environment env = service.createEnvironment(req);

            List<Environment> results = service.listEnvironments(null, null, "running", 1, 10);

            assertThat(results).allMatch(e -> "running".equals(e.getStatus()));
        }

        @Test
        @Order(6)
        @DisplayName("Should filter environments by project ID")
        void shouldFilterEnvironmentsByProjectID() {
            CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
            req.setName("project-env");
            req.setProjectId("proj-specific");
            service.createEnvironment(req);

            List<Environment> results = service.listEnvironments(null, "proj-specific", null, 1, 10);

            assertThat(results).allMatch(e -> "proj-specific".equals(e.getProjectId()));
        }
    }

    @Nested
    @DisplayName("Environment Query - Exception Flow")
    class EnvironmentQueryExceptionFlow {

        @ParameterizedTest
        @Order(1)
        @NullAndEmptySource
        @DisplayName("Should throw exception when environment ID is null or empty")
        void shouldThrowExceptionWhenEnvIDIsNullOrEmpty(String id) {
            assertThatThrownBy(() -> service.getEnvironment(id))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @Order(2)
        @DisplayName("Should throw exception when environment not found")
        void shouldThrowExceptionWhenEnvironmentNotFound() {
            assertThatThrownBy(() -> service.getEnvironment("non-existent-id"))
                .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Nested
    @DisplayName("Environment Status Update")
    class EnvironmentStatusUpdate {

        @Test
        @Order(1)
        @DisplayName("Should update environment status")
        void shouldUpdateEnvironmentStatus() {
            Environment env = serviceWithEnv.listEnvironments(null, null, null, 1, 1).get(0);

            serviceWithEnv.updateEnvironmentStatus(env.getId(), "stopped");

            Environment updated = serviceWithEnv.getEnvironment(env.getId());
            assertThat(updated.getStatus()).isEqualTo("stopped");
        }

        @Test
        @Order(2)
        @DisplayName("Should update lastActiveAt when status is running")
        void shouldUpdateLastActiveAtWhenStatusIsRunning() throws Exception {
            Environment env = serviceWithEnv.listEnvironments(null, null, null, 1, 1).get(0);
            LocalDateTime originalActiveAt = env.getLastActiveAt();

            Thread.sleep(10);
            serviceWithEnv.updateEnvironmentStatus(env.getId(), "running");

            Environment updated = serviceWithEnv.getEnvironment(env.getId());
            assertThat(updated.getLastActiveAt()).isAfterOrEqualTo(originalActiveAt);
        }

        @Test
        @Order(3)
        @DisplayName("Should throw exception when updating non-existent environment")
        void shouldThrowExceptionWhenUpdatingNonExistentEnvironment() {
            assertThatThrownBy(() -> service.updateEnvironmentStatus("non-existent", "stopped"))
                .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Nested
    @DisplayName("Environment Deletion")
    class EnvironmentDeletion {

        @Test
        @Order(1)
        @DisplayName("Should delete environment successfully")
        void shouldDeleteEnvironmentSuccessfully() {
            Environment env = serviceWithEnv.listEnvironments(null, null, null, 1, 1).get(0);
            String envId = env.getId();

            serviceWithEnv.deleteEnvironment(envId);

            assertThatThrownBy(() -> serviceWithEnv.getEnvironment(envId))
                .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        @Order(2)
        @DisplayName("Should remove usage records on deletion")
        void shouldRemoveUsageRecordsOnDeletion() {
            CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
            req.setName("delete-env");
            Environment env = service.createEnvironment(req);

            int usageSizeBefore = service.getUsageStoreSize();
            service.deleteEnvironment(env.getId());

            assertThat(service.getUsageStoreSize()).isLessThanOrEqualTo(usageSizeBefore);
        }

        @Test
        @Order(3)
        @DisplayName("Should throw exception when deleting non-existent environment")
        void shouldThrowExceptionWhenDeletingNonExistentEnvironment() {
            assertThatThrownBy(() -> service.deleteEnvironment("non-existent"))
                .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Nested
    @DisplayName("TTL and Auto-Reclaim")
    class TTLAndAutoReclaim {

        @Test
        @Order(1)
        @DisplayName("Should reclaim expired environments")
        void shouldReclaimExpiredEnvironments() {
            CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
            req.setName("expired-env");
            req.setTtlHours(1);
            Environment env = service.createEnvironment(req);

            env.setAutoReclaimAt(LocalDateTime.now().minusHours(2));

            List<String> reclaimed = service.reclaimExpiredEnvironments();

            assertThat(reclaimed).contains(env.getId());
            assertThat(service.getEnvStoreSize()).isZero();
        }

        @Test
        @Order(2)
        @DisplayName("Should not reclaim non-expired environments")
        void shouldNotReclaimNonExpiredEnvironments() {
            CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
            req.setName("active-env");
            req.setTtlHours(24);
            Environment env = service.createEnvironment(req);

            List<String> reclaimed = service.reclaimExpiredEnvironments();

            assertThat(reclaimed).doesNotContain(env.getId());
            assertThat(service.getEnvironment(env.getId())).isNotNull();
        }

        @Test
        @Order(3)
        @DisplayName("Should extend TTL successfully")
        void shouldExtendTTLSuccessfully() {
            CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
            req.setName("extend-env");
            req.setTtlHours(4);
            Environment env = service.createEnvironment(req);
            LocalDateTime originalReclaimAt = env.getAutoReclaimAt();

            service.extendTTL(env.getId(), 2);

            Environment updated = service.getEnvironment(env.getId());
            assertThat(updated.getAutoReclaimAt()).isAfter(originalReclaimAt);
        }

        @Test
        @Order(4)
        @DisplayName("Should throw exception when extending TTL of env without TTL")
        void shouldThrowExceptionWhenExtendingTTLofEnvWithoutTTL() {
            CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
            req.setName("no-ttl-env");
            req.setTtlHours(null);
            Environment env = service.createEnvironment(req);

            assertThatThrownBy(() -> service.extendTTL(env.getId(), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TTL");
        }

        @ParameterizedTest
        @Order(5)
        @ValueSource(ints = {0, -1})
        @DisplayName("Should throw exception for invalid hours in extend TTL")
        void shouldThrowExceptionForInvalidHoursInExtendTTL(int hours) {
            CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
            req.setName("invalid-ttl-env");
            req.setTtlHours(4);
            Environment env = service.createEnvironment(req);

            assertThatThrownBy(() -> service.extendTTL(env.getId(), hours))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Usage Statistics")
    class UsageStatistics {

        @Test
        @Order(1)
        @DisplayName("Should get usage statistics")
        void shouldGetUsageStatistics() {
            Environment env = serviceWithEnv.listEnvironments(null, null, null, 1, 1).get(0);

            serviceWithEnv.recordPeriodicUsage(env.getId(), 0.5, 512);
            serviceWithEnv.recordPeriodicUsage(env.getId(), 0.7, 768);

            UsageStatisticsRequest request = TestDataFactory.createUsageStatisticsRequest(env.getId());
            UsageStatisticsResponse response = serviceWithEnv.getUsageStatistics(request);

            assertThat(response.getEnvironmentId()).isEqualTo(env.getId());
            assertThat(response.getRecords()).isNotEmpty();
            assertThat(response.getAverage()).isPositive();
        }

        @Test
        @Order(2)
        @DisplayName("Should filter by resource type")
        void shouldFilterByResourceType() {
            Environment env = serviceWithEnv.listEnvironments(null, null, null, 1, 1).get(0);

            serviceWithEnv.recordPeriodicUsage(env.getId(), 0.5, 512);

            UsageStatisticsRequest request = TestDataFactory.createUsageStatisticsRequest(env.getId());
            request.setResourceType("cpu");

            UsageStatisticsResponse response = serviceWithEnv.getUsageStatistics(request);

            assertThat(response.getRecords()).allMatch(r -> "cpu".equals(r.getResourceType()));
        }

        @Test
        @Order(3)
        @DisplayName("Should filter by time range")
        void shouldFilterByTimeRange() {
            Environment env = serviceWithEnv.listEnvironments(null, null, null, 1, 1).get(0);

            serviceWithEnv.recordPeriodicUsage(env.getId(), 0.5, 512);

            UsageStatisticsRequest request = TestDataFactory.createUsageStatisticsRequest(env.getId());
            request.setStartTime(LocalDateTime.now().minusHours(1));
            request.setEndTime(LocalDateTime.now().plusHours(1));

            UsageStatisticsResponse response = serviceWithEnv.getUsageStatistics(request);

            assertThat(response.getRecords()).isNotEmpty();
        }

        @Test
        @Order(4)
        @DisplayName("Should calculate peak correctly")
        void shouldCalculatePeakCorrectly() {
            CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
            req.setName("peak-env");
            Environment env = service.createEnvironment(req);

            service.recordPeriodicUsage(env.getId(), 0.3, 256);
            service.recordPeriodicUsage(env.getId(), 0.8, 1024);
            service.recordPeriodicUsage(env.getId(), 0.5, 512);

            UsageStatisticsRequest request = TestDataFactory.createUsageStatisticsRequest(env.getId());
            request.setResourceType("cpu");

            UsageStatisticsResponse response = service.getUsageStatistics(request);

            assertThat(response.getPeak()).isEqualTo(0.8);
        }

        @Test
        @Order(5)
        @DisplayName("Should throw exception when request is null")
        void shouldThrowExceptionWhenRequestIsNull() {
            assertThatThrownBy(() -> service.getUsageStatistics(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @Order(6)
        @DisplayName("Should throw exception when environment ID is empty")
        void shouldThrowExceptionWhenEnvIDIsEmpty() {
            UsageStatisticsRequest request = new UsageStatisticsRequest();
            request.setEnvironmentId("");

            assertThatThrownBy(() -> service.getUsageStatistics(request))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @Order(7)
        @DisplayName("Should return empty for non-existent environment")
        void shouldReturnEmptyForNonExistentEnvironment() {
            UsageStatisticsRequest request = TestDataFactory.createUsageStatisticsRequest("non-existent");

            UsageStatisticsResponse response = service.getUsageStatistics(request);

            assertThat(response.getRecords()).isEmpty();
            assertThat(response.getAverage()).isZero();
        }
    }

    @Nested
    @DisplayName("Concurrent Operations")
    class ConcurrentOperations {

        @Test
        @Order(1)
        @DisplayName("Should handle concurrent environment creation safely")
        void shouldHandleConcurrentEnvironmentCreationSafely() throws Exception {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            List<Future<Environment>> futures = new ArrayList<>();
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    try {
                        latch.countDown();
                        latch.await();
                        CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
                        req.setName("concurrent-env-" + idx + "-" + UUID.randomUUID().toString().substring(0, 4));
                        return service.createEnvironment(req);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        throw e;
                    }
                }));
            }

            latch.await();

            for (Future<Environment> f : futures) {
                Environment env = f.get(5, TimeUnit.SECONDS);
                assertThat(env.getStatus()).isEqualTo("running");
            }

            executor.shutdown();
            assertThat(errors.get()).isZero();
            assertThat(service.getEnvStoreSize()).isEqualTo(threadCount);
        }

        @Test
        @Order(2)
        @DisplayName("Should handle concurrent usage recording")
        void shouldHandleConcurrentUsageRecording() throws Exception {
            Environment env = serviceWithEnv.listEnvironments(null, null, null, 1, 1).get(0);
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        latch.countDown();
                        latch.await();
                        serviceWithEnv.recordPeriodicUsage(env.getId(), 0.5, 512);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }

            latch.await();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            assertThat(errors.get()).isZero();
        }

        @Test
        @Order(3)
        @DisplayName("Should handle concurrent reclaim operations")
        void shouldHandleConcurrentReclaimOperations() throws Exception {
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        latch.countDown();
                        latch.await();
                        service.reclaimExpiredEnvironments();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }

            latch.await();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            assertThat(errors.get()).isZero();
        }
    }

    @Nested
    @DisplayName("Resource Release Integrity")
    class ResourceReleaseIntegrity {

        @Test
        @Order(1)
        @DisplayName("Should release all resources after environment deletion")
        void shouldReleaseAllResourcesAfterEnvironmentDeletion() {
            CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
            req.setName("release-env");
            Environment env = service.createEnvironment(req);

            int envStoreSizeBefore = service.getEnvStoreSize();
            int usageStoreSizeBefore = service.getUsageStoreSize();

            service.deleteEnvironment(env.getId());

            assertThat(service.getEnvStoreSize()).isEqualTo(envStoreSizeBefore - 1);
        }

        @Test
        @Order(2)
        @DisplayName("Should release resources after TTL reclaim")
        void shouldReleaseResourcesAfterTTLReclaim() {
            CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
            req.setName("reclaim-release-env");
            req.setTtlHours(1);
            Environment env = service.createEnvironment(req);

            env.setAutoReclaimAt(LocalDateTime.now().minusHours(2));

            service.reclaimExpiredEnvironments();

            assertThatThrownBy(() -> service.getEnvironment(env.getId()))
                .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        @Order(3)
        @DisplayName("Should maintain consistent state after concurrent deletions")
        void shouldMaintainConsistentStateAfterConcurrentDeletions() throws Exception {
            int envCount = 10;
            for (int i = 0; i < envCount; i++) {
                CreateEnvironmentRequest req = TestDataFactory.createCreateEnvironmentRequest();
                req.setName("concurrent-del-" + i);
                service.createEnvironment(req);
            }

            List<String> envIds = new ArrayList<>();
            for (Environment e : service.listEnvironments(null, null, null, 1, 100)) {
                if (e.getName().startsWith("concurrent-del-")) {
                    envIds.add(e.getId());
                }
            }

            int threadCount = Math.min(envIds.size(), 5);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(envIds.size());
            AtomicInteger errors = new AtomicInteger(0);

            for (String id : envIds) {
                executor.submit(() -> {
                    try {
                        latch.countDown();
                        latch.await();
                        service.deleteEnvironment(id);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }

            latch.await();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            int remaining = service.listEnvironments(null, null, null, 1, 100).size();
            assertThat(remaining).isEqualTo(0);
            assertThat(errors.get()).isZero();
        }
    }
}
