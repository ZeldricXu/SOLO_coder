package com.delivery.tracker.async;

import com.delivery.tracker.entity.AsyncTask;

/**
 * 异步任务回调接口
 * 支持任务生命周期事件通知
 */
public interface AsyncTaskCallback {

    /**
     * 任务开始执行
     */
    void onStarted(AsyncTaskContext context);

    /**
     * 任务执行完成
     */
    void onCompleted(AsyncTaskContext context, Object result);

    /**
     * 任务执行失败
     */
    void onFailed(AsyncTaskContext context, Throwable throwable);

    /**
     * 任务被取消
     */
    void onCancelled(AsyncTaskContext context);

    /**
     * 任务超时
     */
    void onTimeout(AsyncTaskContext context);

    /**
     * 任务进度更新（可选）
     */
    default void onProgress(AsyncTaskContext context, int progress, String message) {
    }

    /**
     * 空回调实现（默认）
     */
    AsyncTaskCallback EMPTY = new AsyncTaskCallback() {
        @Override public void onStarted(AsyncTaskContext context) {}
        @Override public void onCompleted(AsyncTaskContext context, Object result) {}
        @Override public void onFailed(AsyncTaskContext context, Throwable throwable) {}
        @Override public void onCancelled(AsyncTaskContext context) {}
        @Override public void onTimeout(AsyncTaskContext context) {}
    };
}
