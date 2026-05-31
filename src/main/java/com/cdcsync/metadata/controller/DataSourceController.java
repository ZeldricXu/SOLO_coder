package com.cdcsync.metadata.controller;

import com.cdcsync.common.api.PageResult;
import com.cdcsync.common.api.Result;
import com.cdcsync.metadata.domain.DataSource;
import com.cdcsync.metadata.service.DataSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/metadata/data-sources")
@RequiredArgsConstructor
public class DataSourceController {

    private final DataSourceService dataSourceService;

    @PostMapping
    public Result<DataSource> create(@RequestBody DataSource dataSource) {
        return Result.success(dataSourceService.create(dataSource));
    }

    @PutMapping("/{id}")
    public Result<DataSource> update(@PathVariable String id, @RequestBody DataSource dataSource) {
        dataSource.setId(id);
        return Result.success(dataSourceService.update(dataSource));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        dataSourceService.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<DataSource> findById(@PathVariable String id) {
        return Result.success(dataSourceService.findById(id));
    }

    @GetMapping
    public Result<List<DataSource>> findAll() {
        return Result.success(dataSourceService.findAll());
    }

    @GetMapping("/page")
    public Result<PageResult<DataSource>> findPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(dataSourceService.findPage(pageNum, pageSize));
    }

    @GetMapping("/{id}/exists")
    public Result<Boolean> exists(@PathVariable String id) {
        return Result.success(dataSourceService.exists(id));
    }
}
