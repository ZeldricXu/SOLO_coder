package com.company.dbstudio.core.model;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class Result<T> {

    private final T value;
    private final Throwable error;
    private final boolean success;

    private Result(T value, Throwable error, boolean success) {
        this.value = value;
        this.error = error;
        this.success = success;
    }

    public static <T> Result<T> success(T value) {
        return new Result<>(value, null, true);
    }

    public static <T> Result<T> success() {
        return new Result<>(null, null, true);
    }

    public static <T> Result<T> failure(Throwable error) {
        return new Result<>(null, error, false);
    }

    public static <T> Result<T> failure(String errorMessage) {
        return new Result<>(null, new RuntimeException(errorMessage), false);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    public T getValue() {
        if (!success) {
            throw new IllegalStateException("Result is failure", error);
        }
        return value;
    }

    public T getValueOrDefault(T defaultValue) {
        return success ? value : defaultValue;
    }

    public Throwable getError() {
        if (success) {
            throw new IllegalStateException("Result is success");
        }
        return error;
    }

    public String getErrorMessage() {
        return error != null ? error.getMessage() : "Unknown error";
    }

    public Optional<T> toOptional() {
        return success ? Optional.ofNullable(value) : Optional.empty();
    }

    public Result<T> ifSuccess(Consumer<? super T> consumer) {
        if (success) {
            consumer.accept(value);
        }
        return this;
    }

    public Result<T> ifFailure(Consumer<? super Throwable> consumer) {
        if (!success) {
            consumer.accept(error);
        }
        return this;
    }

    public <U> Result<U> map(Function<? super T, ? extends U> mapper) {
        if (success) {
            try {
                return Result.success(mapper.apply(value));
            } catch (Exception e) {
                return Result.failure(e);
            }
        }
        return Result.failure(error);
    }

    public <U> Result<U> flatMap(Function<? super T, Result<U>> mapper) {
        if (success) {
            try {
                return mapper.apply(value);
            } catch (Exception e) {
                return Result.failure(e);
            }
        }
        return Result.failure(error);
    }

    public T orElseThrow() {
        if (!success) {
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
            throw new RuntimeException(error);
        }
        return value;
    }
}
