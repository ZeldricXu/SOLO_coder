package com.projectservice.service;

import com.projectservice.model.scaffold.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ScaffoldService {
    private final Map<String, ProjectTemplate> templateStore = new ConcurrentHashMap<>();
    private final Map<String, GeneratedProject> projectStore = new ConcurrentHashMap<>();
    private final Semaphore generationSemaphore = new Semaphore(10);
    private final AtomicInteger generationCounter = new AtomicInteger(0);
    private boolean simulateFailure = false;

    public ScaffoldService() {}

    public void setSimulateFailure(boolean simulate) { this.simulateFailure = simulate; }

    public List<ProjectTemplate> listTemplates(String language, List<String> tags, int page, int pageSize) {
        List<ProjectTemplate> result = new ArrayList<>();
        for (ProjectTemplate t : templateStore.values()) {
            if (!"active".equals(t.getStatus())) continue;
            if (language != null && !language.isEmpty()) {
                if (!t.getLanguage().equalsIgnoreCase(language)) continue;
            }
            if (tags != null && !tags.isEmpty()) {
                if (t.getTags() == null || !t.getTags().containsAll(tags)) continue;
            }
            result.add(t);
        }
        result.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, result.size());
        return start < result.size() ? result.subList(start, end) : new ArrayList<>();
    }

    public ProjectTemplate getTemplate(String templateId) {
        if (templateId == null || templateId.isEmpty()) {
            throw new IllegalArgumentException("Template ID must not be empty");
        }
        ProjectTemplate t = templateStore.get(templateId);
        if (t == null) {
            throw new NoSuchElementException("Template not found: " + templateId);
        }
        if (!"active".equals(t.getStatus())) {
            throw new IllegalStateException("Template is not active: " + templateId);
        }
        return t;
    }

    public List<InteractiveQuestion> getInteractiveQuestions(String templateId) {
        ProjectTemplate template = getTemplate(templateId);
        List<InteractiveQuestion> questions = new ArrayList<>();
        if (template.getParameters() != null) {
            for (TemplateParameter param : template.getParameters()) {
                InteractiveQuestion q = new InteractiveQuestion();
                q.setQuestion(param.getDescription());
                q.setParameter(param.getName());
                q.setOptions(param.getOptions());
                q.setDefault(param.getDefaultValue() != null ? param.getDefaultValue().toString() : null);
                q.setCategory(param.getCategory());
                questions.add(q);
            }
        }
        return questions;
    }

    public GeneratedProject generateProject(GenerateProjectRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request must not be null");
        }
        if (request.getTemplateId() == null || request.getTemplateId().isEmpty()) {
            throw new IllegalArgumentException("Template ID must not be empty");
        }
        if (request.getProjectName() == null || request.getProjectName().isEmpty()) {
            throw new IllegalArgumentException("Project name must not be empty");
        }
        if (request.getProjectName().length() > 100) {
            throw new IllegalArgumentException("Project name too long");
        }
        if (request.getParameters() == null || request.getParameters().isEmpty()) {
            throw new IllegalArgumentException("Parameters must not be empty");
        }

        try {
            generationSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Generation interrupted", e);
        }

        try {
            ProjectTemplate template = getTemplate(request.getTemplateId());
            validateParameters(template, request.getParameters());

            if (simulateFailure) {
                throw new RuntimeException("Simulated generation failure");
            }

            String projectId = "proj_" + UUID.randomUUID().toString().substring(0, 8);
            LocalDateTime now = LocalDateTime.now();

            GeneratedProject project = new GeneratedProject();
            project.setId(projectId);
            project.setTemplateId(request.getTemplateId());
            project.setProjectName(request.getProjectName());
            project.setDescription(request.getDescription());
            project.setParameters(request.getParameters());
            project.setOutputPath(request.getOutputPath() != null ? request.getOutputPath() : "/output/" + projectId);
            project.setGeneratedBy("system");
            project.setStatus("generating");
            project.setGeneratedAt(now);
            project.setCreatedAt(now);
            project.setUpdatedAt(now);

            projectStore.put(projectId, project);

            generateProjectFiles(template, project);
            generationCounter.incrementAndGet();

            project.setStatus("completed");
            project.setUpdatedAt(LocalDateTime.now());
            return project;
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException || e instanceof NoSuchElementException
                    || e instanceof IllegalStateException) {
                throw e;
            }
            throw new RuntimeException("Project generation failed: " + e.getMessage(), e);
        } finally {
            generationSemaphore.release();
        }
    }

    private void validateParameters(ProjectTemplate template, Map<String, Object> params) {
        if (template.getParameters() != null) {
            for (TemplateParameter tp : template.getParameters()) {
                if (tp.isRequired() && !params.containsKey(tp.getName())) {
                    throw new IllegalArgumentException("Required parameter missing: " + tp.getName());
                }
            }
        }
    }

    private void generateProjectFiles(ProjectTemplate template, GeneratedProject project) {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public BatchGenerateResult batchGenerateProjects(List<GenerateProjectRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("Requests must not be empty");
        }
        if (requests.size() > 100) {
            throw new IllegalArgumentException("Batch size exceeds limit (max 100)");
        }

        String batchId = "batch_" + UUID.randomUUID().toString().substring(0, 8);
        BatchGenerateResult result = new BatchGenerateResult();
        result.setBatchId(batchId);
        result.setTotal(requests.size());

        ExecutorService executor = Executors.newFixedThreadPool(
            Math.min(requests.size(), 10)
        );
        List<Future<GenerateResultItem>> futures = new ArrayList<>();

        for (GenerateProjectRequest req : requests) {
            futures.add(executor.submit(() -> {
                GenerateResultItem item = new GenerateResultItem();
                item.setProjectName(req.getProjectName());
                try {
                    GeneratedProject p = generateProject(req);
                    item.setStatus("success");
                    item.setProjectId(p.getId());
                } catch (Exception e) {
                    item.setStatus("failed");
                    item.setMessage(e.getMessage());
                }
                return item;
            }));
        }

        int successful = 0;
        int failed = 0;
        List<GenerateResultItem> results = new ArrayList<>();
        for (Future<GenerateResultItem> f : futures) {
            try {
                GenerateResultItem item = f.get();
                results.add(item);
                if ("success".equals(item.getStatus())) successful++;
                else failed++;
            } catch (Exception e) {
                failed++;
            }
        }

        executor.shutdown();

        result.setSuccessful(successful);
        result.setFailed(failed);
        result.setResults(results);
        return result;
    }

    public ProjectTemplate createTemplate(ProjectTemplate template) {
        if (template == null) throw new IllegalArgumentException("Template must not be null");
        if (template.getName() == null || template.getName().isEmpty()) {
            throw new IllegalArgumentException("Template name must not be empty");
        }
        String id = "tpl_" + UUID.randomUUID().toString().substring(0, 8);
        template.setId(id);
        template.setCreatedAt(LocalDateTime.now());
        template.setUpdatedAt(LocalDateTime.now());
        if (template.getStatus() == null) template.setStatus("active");
        templateStore.put(id, template);
        return template;
    }

    public GeneratedProject getGeneratedProject(String projectId) {
        if (projectId == null || projectId.isEmpty()) {
            throw new IllegalArgumentException("Project ID must not be empty");
        }
        GeneratedProject p = projectStore.get(projectId);
        if (p == null) {
            throw new NoSuchElementException("Project not found: " + projectId);
        }
        return p;
    }

    public void deleteTemplate(String templateId) {
        if (templateId == null || templateId.isEmpty()) {
            throw new IllegalArgumentException("Template ID must not be empty");
        }
        ProjectTemplate t = templateStore.get(templateId);
        if (t == null) {
            throw new NoSuchElementException("Template not found: " + templateId);
        }
        t.setStatus("deprecated");
        t.setUpdatedAt(LocalDateTime.now());
    }

    public int getGenerationCounter() { return generationCounter.get(); }
    public int getAvailablePermits() { return generationSemaphore.availablePermits(); }
    public int getTemplateStoreSize() { return templateStore.size(); }
    public int getProjectStoreSize() { return projectStore.size(); }

    public static class GenerateProjectRequest {
        private String templateId;
        private String projectName;
        private String description;
        private Map<String, Object> parameters;
        private String outputPath;

        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }
        public String getProjectName() { return projectName; }
        public void setProjectName(String projectName) { this.projectName = projectName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Map<String, Object> getParameters() { return parameters; }
        public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
        public String getOutputPath() { return outputPath; }
        public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
    }

    public static class InteractiveQuestion {
        private String question;
        private String parameter;
        private List<String> options;
        private String defaultValue;
        private String category;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getParameter() { return parameter; }
        public void setParameter(String parameter) { this.parameter = parameter; }
        public List<String> getOptions() { return options; }
        public void setOptions(List<String> options) { this.options = options; }
        public String getDefault() { return defaultValue; }
        public void setDefault(String defaultValue) { this.defaultValue = defaultValue; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
    }

    public static class BatchGenerateResult {
        private String batchId;
        private int total;
        private int successful;
        private int failed;
        private List<GenerateResultItem> results;

        public String getBatchId() { return batchId; }
        public void setBatchId(String batchId) { this.batchId = batchId; }
        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public int getSuccessful() { return successful; }
        public void setSuccessful(int successful) { this.successful = successful; }
        public int getFailed() { return failed; }
        public void setFailed(int failed) { this.failed = failed; }
        public List<GenerateResultItem> getResults() { return results; }
        public void setResults(List<GenerateResultItem> results) { this.results = results; }
    }

    public static class GenerateResultItem {
        private String projectName;
        private String status;
        private String message;
        private String projectId;

        public String getProjectName() { return projectName; }
        public void setProjectName(String projectName) { this.projectName = projectName; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
    }
}
