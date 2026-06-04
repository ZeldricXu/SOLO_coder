package com.cicd.server.deployment;

import com.cicd.common.enums.DeploymentStrategy;
import com.cicd.server.entity.Deployment;
import com.cicd.server.entity.Environment;
import com.cicd.server.repository.EnvironmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/deployments")
@RequiredArgsConstructor
public class DeploymentController {

    private final DeploymentService deploymentService;
    private final EnvironmentRepository environmentRepository;

    @GetMapping
    public ResponseEntity<Page<Deployment>> listDeployments(
            @RequestParam Long projectId,
            @RequestParam(required = false) Long environmentId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(deploymentService.getDeployments(projectId, environmentId, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Deployment> getDeployment(@PathVariable Long id) {
        Deployment deployment = deploymentService.getDeployment(id);
        return deployment != null ? ResponseEntity.ok(deployment) : ResponseEntity.notFound().build();
    }

    @PostMapping
    @PreAuthorize("@rbacPermissionEvaluator.hasProjectPermission(authentication, #projectId, 'deploy')")
    public ResponseEntity<Deployment> createDeployment(
            @RequestParam Long projectId,
            @RequestBody Map<String, Object> request) {
        Deployment deployment = deploymentService.createDeployment(
            projectId,
            Long.valueOf(request.get("environmentId").toString()),
            (String) request.get("appName"),
            (String) request.get("version"),
            (String) request.get("image"),
            DeploymentStrategy.valueOf((String) request.getOrDefault("strategy", "ROLLING_UPDATE"))
        );
        return ResponseEntity.ok(deployment);
    }

    @PostMapping("/{id}/rollback")
    @PreAuthorize("@rbacPermissionEvaluator.hasProjectPermission(authentication, @deploymentService.getDeployment(#id).project.id, 'deploy')")
    public ResponseEntity<Void> rollbackDeployment(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> request) {
        String reason = request != null ? request.get("reason") : "Manual rollback";
        deploymentService.rollbackDeployment(id, reason);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/environments")
    public ResponseEntity<List<Environment>> listEnvironments() {
        return ResponseEntity.ok(environmentRepository.findByIsActiveTrue());
    }

    @GetMapping("/environments/{id}")
    public ResponseEntity<Environment> getEnvironment(@PathVariable Long id) {
        return environmentRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/environments")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Environment> createEnvironment(@RequestBody Environment environment) {
        environment.setIsActive(true);
        return ResponseEntity.ok(environmentRepository.save(environment));
    }

    @PutMapping("/environments/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Environment> updateEnvironment(
            @PathVariable Long id,
            @RequestBody Environment environment) {
        Environment existing = environmentRepository.findById(id).orElseThrow();
        existing.setName(environment.getName());
        existing.setDescription(environment.getDescription());
        existing.setNamespace(environment.getNamespace());
        existing.setClusterName(environment.getClusterName());
        existing.setIngressDomain(environment.getIngressDomain());
        existing.setRequiresApproval(environment.getRequiresApproval());
        existing.setApprovalMode(environment.getApprovalMode());
        existing.setApproversJson(environment.getApproversJson());
        existing.setIsProtected(environment.getIsProtected());
        return ResponseEntity.ok(environmentRepository.save(existing));
    }

    @DeleteMapping("/environments/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Void> deleteEnvironment(@PathVariable Long id) {
        environmentRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
