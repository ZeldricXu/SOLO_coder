package com.delivery.tracker.controller;

import com.delivery.tracker.common.Result;
import com.delivery.tracker.entity.SchemaMigration;
import com.delivery.tracker.service.DataAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/data")
@RequiredArgsConstructor
public class DataAccessController {

    private final DataAccessService dataAccessService;

    @PostMapping("/migrate")
    public Mono<Result<SchemaMigration>> executeMigration(@RequestBody Map<String, String> request) {
        String version = request.get("version");
        String scriptName = request.get("scriptName");
        String scriptContent = request.get("scriptContent");

        return dataAccessService.executeMigration(version, scriptName, scriptContent)
                .map(Result::success);
    }

    @GetMapping("/migrations")
    public Mono<Result<List<SchemaMigration>>> getAllMigrations() {
        return dataAccessService.getAllMigrations()
                .collectList()
                .map(Result::success);
    }

    @GetMapping("/migrations/current-version")
    public Mono<Result<Map<String, String>>> getCurrentSchemaVersion() {
        return dataAccessService.getCurrentSchemaVersion()
                .map(version -> Result.success(Map.of("version", version)));
    }

    @PostMapping("/migrations/validate")
    public Mono<Result<Map<String, Boolean>>> validateSchemaIntegrity() {
        return dataAccessService.validateSchemaIntegrity()
                .map(valid -> Result.success(Map.of("valid", valid)));
    }

    @DeleteMapping("/migrations/{version}")
    public Mono<Result<Void>> rollbackMigration(@PathVariable String version) {
        return dataAccessService.rollbackMigration(version)
                .then(Mono.just(Result.success()));
    }
}
