package com.cdcsync.cdc.controller;

import com.cdcsync.common.api.PageResult;
import com.cdcsync.common.api.Result;
import com.cdcsync.cdc.domain.CaptureTask;
import com.cdcsync.cdc.service.CaptureTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cdc/tasks")
@RequiredArgsConstructor
public class CaptureTaskController {

    private final CaptureTaskService captureTaskService;

    @GetMapping
    public Result<PageResult<CaptureTask>> list(@RequestParam(defaultValue = "1") int pageNum,
                                                @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(captureTaskService.findPage(pageNum, pageSize));
    }

    @GetMapping("/{id}")
    public Result<CaptureTask> getById(@PathVariable String id) {
        return Result.success(captureTaskService.findById(id));
    }

    @PostMapping
    public Result<CaptureTask> create(@RequestBody CaptureTask task) {
        return Result.success(captureTaskService.create(task));
    }

    @PutMapping
    public Result<CaptureTask> update(@RequestBody CaptureTask task) {
        return Result.success(captureTaskService.update(task));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        captureTaskService.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/start")
    public Result<Void> start(@PathVariable String id) {
        captureTaskService.start(id);
        return Result.success();
    }

    @PostMapping("/{id}/stop")
    public Result<Void> stop(@PathVariable String id) {
        captureTaskService.stop(id);
        return Result.success();
    }

    @PostMapping("/{id}/pause")
    public Result<Void> pause(@PathVariable String id) {
        captureTaskService.pause(id);
        return Result.success();
    }

    @PostMapping("/{id}/resume")
    public Result<Void> resume(@PathVariable String id) {
        captureTaskService.resume(id);
        return Result.success();
    }

    @GetMapping("/{id}/status")
    public Result<String> getStatus(@PathVariable String id) {
        return Result.success(captureTaskService.getStatus(id));
    }
}
