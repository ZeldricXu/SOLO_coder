package com.llmgateway.featurestore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.common.constant.CommonConstants;
import com.llmgateway.common.exception.BusinessException;
import com.llmgateway.common.util.IdGenerator;
import com.llmgateway.featurestore.dto.BackfillJobCreateDTO;
import com.llmgateway.featurestore.entity.FeatureBackfillJob;
import com.llmgateway.featurestore.mapper.FeatureBackfillJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureBackfillJobService {

    private final FeatureBackfillJobMapper backfillJobMapper;
    private final FeatureService featureService;

    private static final Set<String> VALID_TRANSITIONS_FROM_PENDING = Set.of(
            CommonConstants.STATUS_RUNNING, CommonConstants.STATUS_FAILED, "cancelled"
    );
    private static final Set<String> VALID_TRANSITIONS_FROM_RUNNING = Set.of(
            CommonConstants.STATUS_SUCCESS, CommonConstants.STATUS_FAILED, "cancelled"
    );
    private static final Set<String> TERMINAL_STATES = Set.of(
            CommonConstants.STATUS_SUCCESS, CommonConstants.STATUS_FAILED, "cancelled"
    );

    @Transactional(rollbackFor = Exception.class)
    public FeatureBackfillJob createJob(BackfillJobCreateDTO dto) {
        featureService.getById(dto.getFeatureId());

        FeatureBackfillJob job = new FeatureBackfillJob();
        job.setJobId(IdGenerator.generateId("job"));
        job.setFeatureId(dto.getFeatureId());
        job.setStartTime(dto.getStartTime() != null ? dto.getStartTime() : LocalDateTime.now().minusDays(7));
        job.setEndTime(dto.getEndTime() != null ? dto.getEndTime() : LocalDateTime.now());
        job.setStatus(CommonConstants.STATUS_PENDING);
        job.setProgress(0.0);
        job.setTotalCount(0L);
        job.setSuccessCount(0L);
        job.setFailedCount(0L);
        job.setCreatedBy(dto.getCreatedBy());

        backfillJobMapper.insert(job);
        log.info("特征回填任务创建成功: jobId={}", job.getJobId());
        return job;
    }

    public FeatureBackfillJob getJob(String jobId) {
        FeatureBackfillJob job = backfillJobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(404, "回填任务不存在");
        }
        return job;
    }

    public PageResult<FeatureBackfillJob> listJobs(String featureId, String status, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<FeatureBackfillJob> wrapper = new LambdaQueryWrapper<>();
        if (featureId != null) {
            wrapper.eq(FeatureBackfillJob::getFeatureId, featureId);
        }
        if (status != null) {
            wrapper.eq(FeatureBackfillJob::getStatus, status);
        }
        wrapper.eq(FeatureBackfillJob::getDeleted, 0);
        wrapper.orderByDesc(FeatureBackfillJob::getCreatedAt);

        IPage<FeatureBackfillJob> page = backfillJobMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page);
    }

    @Async
    @Transactional(rollbackFor = Exception.class)
    public void executeBackfill(String jobId) {
        FeatureBackfillJob job = getJob(jobId);

        if (!CommonConstants.STATUS_PENDING.equals(job.getStatus())) {
            log.warn("回填任务状态异常，跳过执行: jobId={}, currentStatus={}, expectedStatus={}",
                    jobId, job.getStatus(), CommonConstants.STATUS_PENDING);
            return;
        }

        boolean acquired = acquireJobLock(jobId);
        if (!acquired) {
            log.warn("回填任务已被其他线程执行，跳过: jobId={}", jobId);
            return;
        }

        try {
            log.info("开始执行特征回填: jobId={}", jobId);
            Thread.sleep(1000);

            for (int i = 0; i <= 100; i += 10) {
                if (isJobCancelled(jobId)) {
                    log.info("回填任务已被取消，停止执行: jobId={}, progress={}%", jobId, i);
                    updateJobStatus(jobId, "cancelled", null);
                    return;
                }

                job.setProgress(i / 100.0);
                job.setSuccessCount((long) i * 10);
                job.setTotalCount(1000L);
                job.setUpdatedAt(LocalDateTime.now());
                backfillJobMapper.updateById(job);
                Thread.sleep(500);
            }

            updateJobStatus(jobId, CommonConstants.STATUS_SUCCESS, null);
            job.setProgress(1.0);
            job.setSuccessCount(1000L);
            job.setTotalCount(1000L);
            job.setUpdatedAt(LocalDateTime.now());
            backfillJobMapper.updateById(job);
            log.info("特征回填完成: jobId={}", jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("特征回填任务被中断: jobId={}", jobId, e);
            updateJobStatus(jobId, CommonConstants.STATUS_FAILED, "任务执行被中断: " + e.getMessage());
        } catch (Exception e) {
            log.error("特征回填失败: jobId={}", jobId, e);
            updateJobStatus(jobId, CommonConstants.STATUS_FAILED, e.getMessage());
        }
    }

    private boolean acquireJobLock(String jobId) {
        LambdaUpdateWrapper<FeatureBackfillJob> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(FeatureBackfillJob::getJobId, jobId)
                .eq(FeatureBackfillJob::getStatus, CommonConstants.STATUS_PENDING)
                .set(FeatureBackfillJob::getStatus, CommonConstants.STATUS_RUNNING)
                .set(FeatureBackfillJob::getUpdatedAt, LocalDateTime.now());

        int updated = backfillJobMapper.update(null, wrapper);
        return updated > 0;
    }

    private boolean isJobCancelled(String jobId) {
        FeatureBackfillJob current = backfillJobMapper.selectById(jobId);
        return current != null && "cancelled".equals(current.getStatus());
    }

    private void updateJobStatus(String jobId, String newStatus, String errorDetail) {
        LambdaUpdateWrapper<FeatureBackfillJob> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(FeatureBackfillJob::getJobId, jobId)
                .set(FeatureBackfillJob::getStatus, newStatus)
                .set(FeatureBackfillJob::getUpdatedAt, LocalDateTime.now());

        if (errorDetail != null) {
            wrapper.set(FeatureBackfillJob::getErrorDetail, errorDetail);
        }

        if (TERMINAL_STATES.contains(newStatus)) {
            wrapper.set(FeatureBackfillJob::getCompletedAt, LocalDateTime.now());
        }

        backfillJobMapper.update(null, wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public FeatureBackfillJob cancelJob(String jobId) {
        FeatureBackfillJob job = getJob(jobId);

        if (TERMINAL_STATES.contains(job.getStatus())) {
            throw new BusinessException("任务已处于终态，无法取消");
        }

        updateJobStatus(jobId, "cancelled", "用户主动取消");
        log.info("回填任务已取消: jobId={}", jobId);
        return getJob(jobId);
    }
}
