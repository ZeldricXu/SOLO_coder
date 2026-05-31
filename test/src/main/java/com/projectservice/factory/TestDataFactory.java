package com.projectservice.factory;

import com.projectservice.model.vulnerability.*;
import com.projectservice.model.scaffold.*;
import com.projectservice.model.environment.*;
import com.projectservice.service.*;
import com.projectservice.service.VulnerabilityService.*;
import com.projectservice.service.ScaffoldService.*;
import com.projectservice.service.EnvironmentService.*;

import java.time.LocalDateTime;
import java.util.*;

public class TestDataFactory {

    private static final Random RANDOM = new Random(42);

    private TestDataFactory() {}

    public static SBOMComponent createSBOMComponent(String name, String version) {
        SBOMComponent comp = new SBOMComponent();
        comp.setName(name);
        comp.setVersion(version);
        comp.setPurl("pkg:generic/" + name + "@" + version);
        comp.setType("library");
        comp.setLicense("MIT");
        Map<String, String> hashes = new HashMap<>();
        hashes.put("SHA-256", UUID.randomUUID().toString().replace("-", ""));
        comp.setHashes(hashes);
        return comp;
    }

    public static SBOMComponent createVulnerableComponent() {
        return createSBOMComponent("log4j-core", "2.14.1");
    }

    public static SBOMComponent createSafeComponent() {
        return createSBOMComponent("slf4j-api", "1.7.36");
    }

    public static List<SBOMComponent> createSBOMComponents(int count) {
        List<SBOMComponent> components = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            components.add(createSBOMComponent("lib-" + i, "1.0." + i));
        }
        return components;
    }

    public static Vulnerability createVulnerability(String cveId, String severity, double score) {
        Vulnerability vuln = new Vulnerability();
        vuln.setId(UUID.randomUUID().toString());
        vuln.setCveId(cveId);
        vuln.setSeverity(severity);
        vuln.setScore(score);
        vuln.setDescription("Vulnerability " + cveId + " description");
        vuln.setReferences(Arrays.asList("https://cve.mitre.org/cgi-bin/cvename.cgi?name=" + cveId));
        vuln.setAffectedPkg("log4j-core");
        vuln.setAffectedRange("<2.17.1");
        vuln.setFixedVersion("2.17.1");
        vuln.setFixedPackages(Arrays.asList(
            new FixedPackage("log4j-core", "2.17.1"),
            new FixedPackage("log4j-core", "2.12.4")
        ));
        vuln.setPublishedAt(LocalDateTime.now().minusDays(30));
        vuln.setCreatedAt(LocalDateTime.now());
        vuln.setUpdatedAt(LocalDateTime.now());
        Map<String, Object> extra = new HashMap<>();
        extra.put("cvss_vector", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H");
        vuln.setExtraInfo(extra);
        return vuln;
    }

    public static Vulnerability createCriticalVulnerability() {
        return createVulnerability("CVE-2021-44228", "critical", 10.0);
    }

    public static Vulnerability createHighVulnerability() {
        return createVulnerability("CVE-2023-1234", "high", 7.5);
    }

    public static Vulnerability createMediumVulnerability() {
        return createVulnerability("CVE-2023-5678", "medium", 5.5);
    }

    public static Vulnerability createLowVulnerability() {
        return createVulnerability("CVE-2023-9012", "low", 2.5);
    }

    public static List<Vulnerability> createVulnerabilityList(int critical, int high, int medium, int low) {
        List<Vulnerability> list = new ArrayList<>();
        for (int i = 0; i < critical; i++) list.add(createVulnerability("CVE-CRIT-" + i, "critical", 9.5));
        for (int i = 0; i < high; i++) list.add(createVulnerability("CVE-HIGH-" + i, "high", 7.5));
        for (int i = 0; i < medium; i++) list.add(createVulnerability("CVE-MED-" + i, "medium", 5.5));
        for (int i = 0; i < low; i++) list.add(createVulnerability("CVE-LOW-" + i, "low", 2.5));
        return list;
    }

    public static SBOMAnalysisRequest createSBOMAnalysisRequest() {
        SBOMAnalysisRequest req = new SBOMAnalysisRequest();
        req.setProjectId("proj-" + UUID.randomUUID().toString().substring(0, 8));
        req.setFormat("json");
        req.setComponents(Arrays.asList(createVulnerableComponent(), createSafeComponent()));
        req.setSbomData("{\"components\":[...]}");
        req.setMetadata(new HashMap<>());
        return req;
    }

    public static SBOMAnalysisRequest createSBOMAnalysisRequestWithData(String projectId, String sbomData) {
        SBOMAnalysisRequest req = new SBOMAnalysisRequest();
        req.setProjectId(projectId);
        req.setFormat("json");
        req.setComponents(createSBOMComponents(5));
        req.setSbomData(sbomData);
        return req;
    }

    public static SBOMAnalysisRequest createInvalidSBOMRequest() {
        SBOMAnalysisRequest req = new SBOMAnalysisRequest();
        req.setProjectId("");
        req.setSbomData("");
        return req;
    }

    public static CVEQueryRequest createCVEQueryRequest() {
        CVEQueryRequest req = new CVEQueryRequest();
        req.setPackageName("log4j-core");
        req.setSeverity("critical");
        req.setPage(1);
        req.setPageSize(20);
        return req;
    }

    public static CVEQueryRequest createEmptyCVEQueryRequest() {
        CVEQueryRequest req = new CVEQueryRequest();
        req.setPage(1);
        req.setPageSize(20);
        return req;
    }

    public static ProjectTemplate createProjectTemplate() {
        ProjectTemplate tpl = new ProjectTemplate();
        tpl.setId(UUID.randomUUID().toString());
        tpl.setName("Spring Boot Microservice");
        tpl.setDescription("Standard Spring Boot microservice template");
        tpl.setLanguage("java");
        tpl.setFramework("spring-boot");
        tpl.setVersion("1.0.0");
        tpl.setParameters(createTemplateParameters());
        tpl.setStructure(new HashMap<>());
        tpl.setTags(Arrays.asList("java", "spring", "microservice"));
        tpl.setOwner("admin");
        tpl.setPublic(true);
        tpl.setStatus("active");
        tpl.setCreatedAt(LocalDateTime.now());
        tpl.setUpdatedAt(LocalDateTime.now());
        return tpl;
    }

    public static List<TemplateParameter> createTemplateParameters() {
        List<TemplateParameter> params = new ArrayList<>();
        params.add(new TemplateParameter(
            "packageName", "Base package name", "string", "com.example",
            true, null, "^[a-z]+(\\.[a-z]+)*$", "project"
        ));
        params.add(new TemplateParameter(
            "javaVersion", "Java version", "string", "17",
            true, Arrays.asList("8", "11", "17", "21"), null, "project"
        ));
        params.add(new TemplateParameter(
            "buildTool", "Build tool", "string", "maven",
            true, Arrays.asList("maven", "gradle"), null, "build"
        ));
        params.add(new TemplateParameter(
            "description", "Project description", "string", "",
            false, null, null, "project"
        ));
        return params;
    }

    public static GenerateProjectRequest createGenerateProjectRequest(String templateId) {
        GenerateProjectRequest req = new GenerateProjectRequest();
        req.setTemplateId(templateId);
        req.setProjectName("my-service");
        req.setDescription("A new microservice");
        Map<String, Object> params = new HashMap<>();
        params.put("packageName", "com.example.myservice");
        params.put("javaVersion", "17");
        params.put("buildTool", "maven");
        req.setParameters(params);
        req.setOutputPath("/workspace/generated");
        return req;
    }

    public static GenerateProjectRequest createGenerateProjectRequestWithMissingParams(String templateId) {
        GenerateProjectRequest req = new GenerateProjectRequest();
        req.setTemplateId(templateId);
        req.setProjectName("my-service");
        Map<String, Object> params = new HashMap<>();
        req.setParameters(params);
        return req;
    }

    public static List<GenerateProjectRequest> createBatchGenerateRequests(int count, String templateId) {
        List<GenerateProjectRequest> requests = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            GenerateProjectRequest req = createGenerateProjectRequest(templateId);
            req.setProjectName("batch-service-" + i);
            requests.add(req);
        }
        return requests;
    }

    public static Environment createEnvironment() {
        Environment env = new Environment();
        env.setId("env-" + UUID.randomUUID().toString().substring(0, 8));
        env.setName("preview-env-1");
        env.setType("preview");
        env.setStatus("running");
        env.setOwner("tester1");
        env.setProjectId("proj-001");
        env.setConfiguration(new HashMap<>());
        env.setResources(new HashMap<>());
        env.setCreatedAt(LocalDateTime.now());
        env.setUpdatedAt(LocalDateTime.now());
        env.setLastActiveAt(LocalDateTime.now());
        return env;
    }

    public static Environment createEnvironmentWithTTL(int ttlHours) {
        Environment env = createEnvironment();
        env.setTtl(java.time.Duration.ofHours(ttlHours));
        env.setAutoReclaimAt(LocalDateTime.now().plusHours(ttlHours));
        return env;
    }

    public static Environment createExpiredEnvironment() {
        Environment env = createEnvironment();
        env.setTtl(java.time.Duration.ofHours(1));
        env.setAutoReclaimAt(LocalDateTime.now().minusHours(2));
        return env;
    }

    public static CreateEnvironmentRequest createCreateEnvironmentRequest() {
        CreateEnvironmentRequest req = new CreateEnvironmentRequest();
        req.setName("preview-test-" + RANDOM.nextInt(10000));
        req.setType("preview");
        req.setOwner("tester1");
        req.setProjectId("proj-001");
        req.setConfiguration(new HashMap<>());
        req.setResources(new HashMap<>());
        req.setTtlHours(4);
        return req;
    }

    public static CreateEnvironmentRequest createInvalidCreateEnvironmentRequest() {
        CreateEnvironmentRequest req = new CreateEnvironmentRequest();
        req.setName("");
        req.setType("");
        req.setOwner("");
        req.setProjectId("");
        return req;
    }

    public static EnvironmentUsage createEnvironmentUsage(String envId, String resourceType, double value) {
        return new EnvironmentUsage(
            UUID.randomUUID().toString(),
            envId, resourceType, value,
            LocalDateTime.now()
        );
    }

    public static List<EnvironmentUsage> createUsageRecords(String envId, int count) {
        List<EnvironmentUsage> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            records.add(createEnvironmentUsage(envId, "cpu", 0.3 + RANDOM.nextDouble() * 0.5));
            records.add(createEnvironmentUsage(envId, "memory", 256 + RANDOM.nextInt(512)));
        }
        return records;
    }

    public static UsageStatisticsRequest createUsageStatisticsRequest(String envId) {
        UsageStatisticsRequest req = new UsageStatisticsRequest();
        req.setEnvironmentId(envId);
        req.setResourceType("cpu");
        req.setStartTime(LocalDateTime.now().minusHours(1));
        req.setEndTime(LocalDateTime.now());
        return req;
    }

    public static VulnerabilityService createVulnerabilityServiceWithData() {
        VulnerabilityService service = new VulnerabilityService();
        service.registerCVE(createCriticalVulnerability());
        service.registerCVE(createHighVulnerability());
        service.registerCVE(createMediumVulnerability());
        return service;
    }

    public static ScaffoldService createScaffoldServiceWithTemplate() {
        ScaffoldService service = new ScaffoldService();
        ProjectTemplate tpl = createProjectTemplate();
        service.createTemplate(tpl);
        return service;
    }

    public static EnvironmentService createEnvironmentServiceWithEnv() {
        EnvironmentService service = new EnvironmentService();
        CreateEnvironmentRequest req = createCreateEnvironmentRequest();
        service.createEnvironment(req);
        return service;
    }
}
