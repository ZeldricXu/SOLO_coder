package com.cdcsync.vectorindex.controller;

import com.cdcsync.common.api.PageResult;
import com.cdcsync.common.api.Result;
import com.cdcsync.vectorindex.domain.VectorIndex;
import com.cdcsync.vectorindex.service.VectorIndexService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vector-indexes")
@RequiredArgsConstructor
public class VectorIndexController {

    private final VectorIndexService vectorIndexService;

    @PostMapping
    public Result<VectorIndex> create(@Valid @RequestBody VectorIndex index) {
        return Result.success(vectorIndexService.create(index));
    }

    @PutMapping("/{id}")
    public Result<VectorIndex> update(@PathVariable String id, @Valid @RequestBody VectorIndex index) {
        index.setId(id);
        return Result.success(vectorIndexService.update(index));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        vectorIndexService.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<VectorIndex> getById(@PathVariable String id) {
        return Result.success(vectorIndexService.findById(id));
    }

    @GetMapping
    public Result<PageResult<VectorIndex>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(vectorIndexService.findPage(pageNum, pageSize));
    }

    @PostMapping("/{id}/build")
    public Result<Void> buildIndex(@PathVariable String id, @Valid @RequestBody BuildRequest request) {
        vectorIndexService.buildIndex(id, request.getVectors());
        return Result.success();
    }

    @PostMapping("/{id}/search")
    public Result<List<Long>> search(@PathVariable String id, @Valid @RequestBody SearchRequest request) {
        return Result.success(vectorIndexService.search(id, request.getQueryVector(), request.getTopK()));
    }

    @PostMapping("/{id}/vectors")
    public Result<Void> addVectors(@PathVariable String id, @Valid @RequestBody AddVectorsRequest request) {
        vectorIndexService.addVectors(id, request.getVectors());
        return Result.success();
    }

    @DeleteMapping("/{id}/vectors")
    public Result<Void> deleteVectors(@PathVariable String id, @Valid @RequestBody DeleteVectorsRequest request) {
        vectorIndexService.deleteVectors(id, request.getIds());
        return Result.success();
    }

    @Data
    public static class BuildRequest {
        @NotEmpty(message = "Vectors cannot be empty")
        private List<float[]> vectors;
    }

    @Data
    public static class SearchRequest {
        @NotNull(message = "Query vector cannot be null")
        private float[] queryVector;

        @NotNull(message = "TopK cannot be null")
        private Integer topK = 10;
    }

    @Data
    public static class AddVectorsRequest {
        @NotEmpty(message = "Vectors cannot be empty")
        private List<float[]> vectors;
    }

    @Data
    public static class DeleteVectorsRequest {
        @NotEmpty(message = "Ids cannot be empty")
        private List<Long> ids;
    }
}
