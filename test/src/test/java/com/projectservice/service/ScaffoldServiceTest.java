package com.projectservice.service;

import com.projectservice.factory.TestDataFactory;
import com.projectservice.model.scaffold.*;
import com.projectservice.service.ScaffoldService.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Scaffold Service Test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScaffoldServiceTest {

    private ScaffoldService service;
    private ScaffoldService serviceWithTemplate;

    @BeforeEach
    void setUp() {
        service = new ScaffoldService();
        serviceWithTemplate = TestDataFactory.createScaffoldServiceWithTemplate();
    }

    @Nested
    @DisplayName("Template CRUD - Normal Flow")
    class TemplateCRUDNormalFlow {

        @Test
        @Order(1)
        @DisplayName("Should create template successfully")
        void shouldCreateTemplateSuccessfully() {
            ProjectTemplate template = TestDataFactory.createProjectTemplate();

            ProjectTemplate created = service.createTemplate(template);

            assertThat(created.getId()).isNotBlank();
            assertThat(created.getName()).isEqualTo(template.getName());
            assertThat(created.getStatus()).isEqualTo("active");
            assertThat(created.getCreatedAt()).isNotNull();
        }

        @Test
        @Order(2)
        @DisplayName("Should get template by ID")
        void shouldGetTemplateByID() {
            ProjectTemplate template = serviceWithTemplate.getTemplate(
                serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId()
            );

            assertThat(template).isNotNull();
            assertThat(template.getStatus()).isEqualTo("active");
        }

        @Test
        @Order(3)
        @DisplayName("Should list templates with pagination")
        void shouldListTemplatesWithPagination() {
            for (int i = 0; i < 5; i++) {
                ProjectTemplate tpl = TestDataFactory.createProjectTemplate();
                tpl.setName("Template " + i);
                service.createTemplate(tpl);
            }

            List<ProjectTemplate> page1 = service.listTemplates(null, null, 1, 2);
            List<ProjectTemplate> page2 = service.listTemplates(null, null, 2, 2);

            assertThat(page1).hasSize(2);
            assertThat(page2).hasSize(2);
        }

        @Test
        @Order(4)
        @DisplayName("Should list templates filtered by language")
        void shouldListTemplatesFilteredByLanguage() {
            ProjectTemplate javaTpl = TestDataFactory.createProjectTemplate();
            javaTpl.setName("Java Service");
            javaTpl.setLanguage("java");
            service.createTemplate(javaTpl);

            ProjectTemplate goTpl = TestDataFactory.createProjectTemplate();
            goTpl.setName("Go Service");
            goTpl.setLanguage("go");
            service.createTemplate(goTpl);

            List<ProjectTemplate> javaTemplates = service.listTemplates("java", null, 1, 10);

            assertThat(javaTemplates).allMatch(t -> "java".equalsIgnoreCase(t.getLanguage()));
        }

        @Test
        @Order(5)
        @DisplayName("Should list templates filtered by tags")
        void shouldListTemplatesFilteredByTags() {
            ProjectTemplate tpl = TestDataFactory.createProjectTemplate();
            tpl.setName("Tagged Template");
            tpl.setTags(Arrays.asList("java", "spring", "web"));
            service.createTemplate(tpl);

            List<ProjectTemplate> results = service.listTemplates(
                null, Arrays.asList("spring"), 1, 10
            );

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).getTags()).contains("spring");
        }

        @Test
        @Order(6)
        @DisplayName("Should deprecate template on delete")
        void shouldDeprecateTemplateOnDelete() {
            ProjectTemplate tpl = TestDataFactory.createProjectTemplate();
            tpl.setName("To Delete");
            ProjectTemplate created = service.createTemplate(tpl);

            service.deleteTemplate(created.getId());

            ProjectTemplate deleted = service.listTemplates(null, null, 1, 100).stream()
                .filter(t -> t.getId().equals(created.getId()))
                .findFirst().orElse(null);

            assertThat(deleted).isNull();
        }
    }

    @Nested
    @DisplayName("Template CRUD - Exception Flow")
    class TemplateCRUDExceptionFlow {

        @Test
        @Order(1)
        @DisplayName("Should throw exception when template is null")
        void shouldThrowExceptionWhenTemplateIsNull() {
            assertThatThrownBy(() -> service.createTemplate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
        }

        @ParameterizedTest
        @Order(2)
        @NullAndEmptySource
        @DisplayName("Should throw exception when template name is null or empty")
        void shouldThrowExceptionWhenTemplateNameIsNullOrEmpty(String name) {
            ProjectTemplate tpl = new ProjectTemplate();
            tpl.setName(name);

            assertThatThrownBy(() -> service.createTemplate(tpl))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @Order(3)
        @DisplayName("Should throw exception when getting non-existent template")
        void shouldThrowExceptionWhenGettingNonExistentTemplate() {
            assertThatThrownBy(() -> service.getTemplate("non-existent-id"))
                .isInstanceOf(NoSuchElementException.class);
        }

        @ParameterizedTest
        @Order(4)
        @NullAndEmptySource
        @DisplayName("Should throw exception when template ID is null or empty")
        void shouldThrowExceptionWhenTemplateIDIsNullOrEmpty(String id) {
            assertThatThrownBy(() -> service.getTemplate(id))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @Order(5)
        @DisplayName("Should throw exception when deleting non-existent template")
        void shouldThrowExceptionWhenDeletingNonExistentTemplate() {
            assertThatThrownBy(() -> service.deleteTemplate("non-existent-id"))
                .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Nested
    @DisplayName("Interactive Questions")
    class InteractiveQuestionsTest {

        @Test
        @Order(1)
        @DisplayName("Should return interactive questions for template")
        void shouldReturnInteractiveQuestionsForTemplate() {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();

            List<InteractiveQuestion> questions = serviceWithTemplate.getInteractiveQuestions(templateId);

            assertThat(questions).isNotEmpty();
            assertThat(questions).allMatch(q -> q.getParameter() != null);
            assertThat(questions).allMatch(q -> q.getQuestion() != null);
        }

        @Test
        @Order(2)
        @DisplayName("Should return empty questions for template without parameters")
        void shouldReturnEmptyQuestionsForTemplateWithoutParameters() {
            ProjectTemplate tpl = TestDataFactory.createProjectTemplate();
            tpl.setName("No Params Template");
            tpl.setParameters(new ArrayList<>());
            ProjectTemplate created = service.createTemplate(tpl);

            List<InteractiveQuestion> questions = service.getInteractiveQuestions(created.getId());

            assertThat(questions).isEmpty();
        }
    }

    @Nested
    @DisplayName("Project Generation - Normal Flow")
    class ProjectGenerationNormalFlow {

        @Test
        @Order(1)
        @DisplayName("Should generate project successfully")
        void shouldGenerateProjectSuccessfully() {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();
            GenerateProjectRequest request = TestDataFactory.createGenerateProjectRequest(templateId);

            GeneratedProject project = serviceWithTemplate.generateProject(request);

            assertThat(project.getId()).isNotBlank();
            assertThat(project.getStatus()).isEqualTo("completed");
            assertThat(project.getProjectName()).isEqualTo(request.getProjectName());
            assertThat(project.getTemplateId()).isEqualTo(templateId);
        }

        @Test
        @Order(2)
        @DisplayName("Should generate multiple projects")
        void shouldGenerateMultipleProjects() {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();

            for (int i = 0; i < 5; i++) {
                GenerateProjectRequest req = TestDataFactory.createGenerateProjectRequest(templateId);
                req.setProjectName("multi-proj-" + i);
                GeneratedProject project = serviceWithTemplate.generateProject(req);
                assertThat(project.getStatus()).isEqualTo("completed");
            }

            assertThat(serviceWithTemplate.getGenerationCounter()).isEqualTo(5);
        }

        @Test
        @Order(3)
        @DisplayName("Should get generated project")
        void shouldGetGeneratedProject() {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();
            GenerateProjectRequest request = TestDataFactory.createGenerateProjectRequest(templateId);
            GeneratedProject created = serviceWithTemplate.generateProject(request);

            GeneratedProject retrieved = serviceWithTemplate.getGeneratedProject(created.getId());

            assertThat(retrieved.getId()).isEqualTo(created.getId());
        }

        @Test
        @Order(4)
        @DisplayName("Should set default output path when not provided")
        void shouldSetDefaultOutputPathWhenNotProvided() {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();
            GenerateProjectRequest request = TestDataFactory.createGenerateProjectRequest(templateId);
            request.setOutputPath(null);

            GeneratedProject project = serviceWithTemplate.generateProject(request);

            assertThat(project.getOutputPath()).isNotBlank();
            assertThat(project.getOutputPath()).startsWith("/output/");
        }
    }

    @Nested
    @DisplayName("Project Generation - Exception Flow")
    class ProjectGenerationExceptionFlow {

        @Test
        @Order(1)
        @DisplayName("Should throw exception when request is null")
        void shouldThrowExceptionWhenRequestIsNull() {
            assertThatThrownBy(() -> service.generateProject(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
        }

        @Test
        @Order(2)
        @DisplayName("Should throw exception when template ID is empty")
        void shouldThrowExceptionWhenTemplateIDIsEmpty() {
            GenerateProjectRequest request = new GenerateProjectRequest();
            request.setTemplateId("");
            request.setProjectName("test");
            request.setParameters(new HashMap<>());

            assertThatThrownBy(() -> service.generateProject(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Template ID");
        }

        @ParameterizedTest
        @Order(3)
        @NullAndEmptySource
        @DisplayName("Should throw exception when project name is null or empty")
        void shouldThrowExceptionWhenProjectNameIsNullOrEmpty(String name) {
            GenerateProjectRequest request = new GenerateProjectRequest();
            request.setTemplateId("tpl-001");
            request.setProjectName(name);
            request.setParameters(new HashMap<>());

            assertThatThrownBy(() -> service.generateProject(request))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @Order(4)
        @DisplayName("Should throw exception when project name exceeds limit")
        void shouldThrowExceptionWhenProjectNameExceedsLimit() {
            GenerateProjectRequest request = new GenerateProjectRequest();
            request.setTemplateId("tpl-001");
            request.setProjectName("a".repeat(101));
            request.setParameters(new HashMap<>());

            assertThatThrownBy(() -> service.generateProject(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
        }

        @Test
        @Order(5)
        @DisplayName("Should throw exception when parameters is null")
        void shouldThrowExceptionWhenParametersIsNull() {
            GenerateProjectRequest request = new GenerateProjectRequest();
            request.setTemplateId("tpl-001");
            request.setProjectName("test");
            request.setParameters(null);

            assertThatThrownBy(() -> service.generateProject(request))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @Order(6)
        @DisplayName("Should throw exception when required parameter is missing")
        void shouldThrowExceptionWhenRequiredParameterIsMissing() {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();
            GenerateProjectRequest request = TestDataFactory.createGenerateProjectRequestWithMissingParams(templateId);

            assertThatThrownBy(() -> serviceWithTemplate.generateProject(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Project generation failed");
        }

        @Test
        @Order(7)
        @DisplayName("Should throw exception when template is not found")
        void shouldThrowExceptionWhenTemplateNotFound() {
            GenerateProjectRequest request = TestDataFactory.createGenerateProjectRequest("non-existent-tpl");

            assertThatThrownBy(() -> service.generateProject(request))
                .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Batch Generation - Normal Flow")
    class BatchGenerationNormalFlow {

        @Test
        @Order(1)
        @DisplayName("Should batch generate projects successfully")
        void shouldBatchGenerateProjectsSuccessfully() {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();
            List<GenerateProjectRequest> requests = TestDataFactory.createBatchGenerateRequests(5, templateId);

            BatchGenerateResult result = serviceWithTemplate.batchGenerateProjects(requests);

            assertThat(result.getBatchId()).isNotBlank();
            assertThat(result.getTotal()).isEqualTo(5);
            assertThat(result.getSuccessful()).isEqualTo(5);
            assertThat(result.getFailed()).isZero();
            assertThat(result.getResults()).hasSize(5);
        }

        @Test
        @Order(2)
        @DisplayName("Should handle partial failure in batch")
        void shouldHandlePartialFailureInBatch() {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();
            List<GenerateProjectRequest> requests = new ArrayList<>();

            GenerateProjectRequest goodReq = TestDataFactory.createGenerateProjectRequest(templateId);
            goodReq.setProjectName("good-proj");
            requests.add(goodReq);

            GenerateProjectRequest badReq = new GenerateProjectRequest();
            badReq.setTemplateId("non-existent");
            badReq.setProjectName("bad-proj");
            badReq.setParameters(new HashMap<>());
            requests.add(badReq);

            BatchGenerateResult result = serviceWithTemplate.batchGenerateProjects(requests);

            assertThat(result.getTotal()).isEqualTo(2);
            assertThat(result.getSuccessful()).isEqualTo(1);
            assertThat(result.getFailed()).isEqualTo(1);
        }

        @Test
        @Order(3)
        @DisplayName("Should return correct result items")
        void shouldReturnCorrectResultItems() {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();
            List<GenerateProjectRequest> requests = TestDataFactory.createBatchGenerateRequests(3, templateId);

            BatchGenerateResult result = serviceWithTemplate.batchGenerateProjects(requests);

            for (GenerateResultItem item : result.getResults()) {
                assertThat(item.getProjectName()).isNotBlank();
                if ("success".equals(item.getStatus())) {
                    assertThat(item.getProjectId()).isNotBlank();
                }
            }
        }
    }

    @Nested
    @DisplayName("Batch Generation - Exception Flow")
    class BatchGenerationExceptionFlow {

        @Test
        @Order(1)
        @DisplayName("Should throw exception when requests is null")
        void shouldThrowExceptionWhenRequestsIsNull() {
            assertThatThrownBy(() -> service.batchGenerateProjects(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @Order(2)
        @DisplayName("Should throw exception when requests is empty")
        void shouldThrowExceptionWhenRequestsIsEmpty() {
            assertThatThrownBy(() -> service.batchGenerateProjects(new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @Order(3)
        @DisplayName("Should throw exception when batch exceeds 100")
        void shouldThrowExceptionWhenBatchExceeds100() {
            List<GenerateProjectRequest> requests = new ArrayList<>();
            for (int i = 0; i < 101; i++) {
                GenerateProjectRequest req = new GenerateProjectRequest();
                req.setTemplateId("tpl-001");
                req.setProjectName("proj-" + i);
                req.setParameters(new HashMap<>());
                requests.add(req);
            }

            assertThatThrownBy(() -> service.batchGenerateProjects(requests))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
        }
    }

    @Nested
    @DisplayName("Concurrent Operations")
    class ConcurrentOperations {

        @Test
        @Order(1)
        @DisplayName("Should handle concurrent project generation safely")
        void shouldHandleConcurrentProjectGenerationSafely() throws Exception {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();
            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            List<Future<GeneratedProject>> futures = new ArrayList<>();
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    try {
                        latch.countDown();
                        latch.await();
                        GenerateProjectRequest req = TestDataFactory.createGenerateProjectRequest(templateId);
                        req.setProjectName("concurrent-proj-" + idx);
                        return serviceWithTemplate.generateProject(req);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        throw e;
                    }
                }));
            }

            latch.await();

            for (Future<GeneratedProject> f : futures) {
                GeneratedProject p = f.get(5, TimeUnit.SECONDS);
                assertThat(p.getStatus()).isEqualTo("completed");
            }

            executor.shutdown();
            assertThat(errors.get()).isZero();
            assertThat(serviceWithTemplate.getGenerationCounter()).isEqualTo(threadCount);
        }

        @Test
        @Order(2)
        @DisplayName("Should handle concurrent batch generation")
        void shouldHandleConcurrentBatchGeneration() throws Exception {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();
            int batchCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(batchCount);
            CountDownLatch latch = new CountDownLatch(batchCount);
            List<Future<BatchGenerateResult>> futures = new ArrayList<>();

            for (int i = 0; i < batchCount; i++) {
                futures.add(executor.submit(() -> {
                    latch.countDown();
                    latch.await();
                    List<GenerateProjectRequest> requests = TestDataFactory.createBatchGenerateRequests(3, templateId);
                    return serviceWithTemplate.batchGenerateProjects(requests);
                }));
            }

            latch.await();

            for (Future<BatchGenerateResult> f : futures) {
                BatchGenerateResult result = f.get(10, TimeUnit.SECONDS);
                assertThat(result.getSuccessful()).isEqualTo(3);
            }

            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("Resource Release Integrity")
    class ResourceReleaseIntegrity {

        @Test
        @Order(1)
        @DisplayName("Should release semaphore after successful generation")
        void shouldReleaseSemaphoreAfterSuccessfulGeneration() {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();
            GenerateProjectRequest request = TestDataFactory.createGenerateProjectRequest(templateId);

            int permitsBefore = serviceWithTemplate.getAvailablePermits();

            serviceWithTemplate.generateProject(request);

            assertThat(serviceWithTemplate.getAvailablePermits()).isEqualTo(permitsBefore);
        }

        @Test
        @Order(2)
        @DisplayName("Should release semaphore after failed generation")
        void shouldReleaseSemaphoreAfterFailedGeneration() {
            int permitsBefore = service.getAvailablePermits();

            GenerateProjectRequest request = new GenerateProjectRequest();
            request.setTemplateId("non-existent");
            request.setProjectName("test");
            request.setParameters(new HashMap<>());

            try {
                service.generateProject(request);
            } catch (RuntimeException ignored) {
            }

            assertThat(service.getAvailablePermits()).isEqualTo(permitsBefore);
        }

        @Test
        @Order(3)
        @DisplayName("Should release semaphore in concurrent scenarios")
        void shouldReleaseSemaphoreInConcurrentScenarios() throws Exception {
            String templateId = serviceWithTemplate.listTemplates(null, null, 1, 1).get(0).getId();
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            int initialPermits = serviceWithTemplate.getAvailablePermits();

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        latch.countDown();
                        latch.await();
                        GenerateProjectRequest req = TestDataFactory.createGenerateProjectRequest(templateId);
                        req.setProjectName("resource-proj-" + idx);
                        serviceWithTemplate.generateProject(req);
                    } catch (Exception ignored) {
                    }
                });
            }

            latch.await();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            assertThat(serviceWithTemplate.getAvailablePermits()).isEqualTo(initialPermits);
        }
    }
}
