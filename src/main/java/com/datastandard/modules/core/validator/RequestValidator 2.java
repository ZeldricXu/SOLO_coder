package com.datastandard.modules.core.validator;

import com.datastandard.modules.core.dto.TransformRequest;
import reactor.core.publisher.Mono;

/**
 * 请求验证器接口，定义参数校验的标准行为。
 *
 * @param <T> 请求类型
 */
public interface RequestValidator<T> {

    /**
     * 验证请求参数的合法性。
     *
     * @param request 待验证的请求对象
     * @return 验证通过的请求对象（Mono包装）
     * @throws com.datastandard.common.exception.ValidationException 当参数校验失败时抛出
     */
    Mono<T> validate(T request);
}
