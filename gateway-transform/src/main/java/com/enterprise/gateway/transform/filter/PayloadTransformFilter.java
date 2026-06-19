package com.enterprise.gateway.transform.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;

import com.enterprise.gateway.transform.converter.PayloadConverter;
import com.enterprise.gateway.transform.converter.PayloadConverterFactory;

@Slf4j
@Component
@RequiredArgsConstructor
public class PayloadTransformFilter implements GlobalFilter, Ordered {

    private final PayloadConverterFactory converterFactory;
    private final DataBufferFactory bufferFactory = new NettyDataBufferFactory(io.netty.buffer.PooledByteBufAllocator.DEFAULT);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        MediaType requestContentType = request.getHeaders().getContentType();
        MediaType acceptType = request.getHeaders().getAccept().stream().findFirst().orElse(null);

        ServerHttpRequest mutatedRequest = transformRequest(request, requestContentType, acceptType);
        ServerHttpResponse mutatedResponse = transformResponse(response, requestContentType, acceptType);

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .response(mutatedResponse)
                .build();

        return chain.filter(mutatedExchange);
    }

    private ServerHttpRequest transformRequest(ServerHttpRequest request, MediaType contentType, MediaType acceptType) {
        if (contentType == null || acceptType == null || contentType.isCompatibleWith(acceptType)) {
            return request;
        }

        Optional<PayloadConverter> converterOpt = converterFactory.getConverter(contentType, acceptType);
        if (converterOpt.isEmpty()) {
            return request;
        }

        PayloadConverter converter = converterOpt.get();

        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return super.getBody()
                        .flatMap(buffer -> converter.convert(buffer, contentType, acceptType))
                        .doOnError(e -> log.error("Request payload conversion failed", e));
            }

            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = HttpHeaders.writableHttpHeaders(super.getHeaders());
                headers.setContentType(acceptType);
                return headers;
            }
        };
    }

    private ServerHttpResponse transformResponse(ServerHttpResponse response, MediaType requestContentType, MediaType acceptType) {
        if (acceptType == null) {
            return response;
        }

        return new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                MediaType responseContentType = getDelegate().getHeaders().getContentType();
                if (responseContentType == null || responseContentType.isCompatibleWith(acceptType)) {
                    return super.writeWith(body);
                }

                Optional<PayloadConverter> converterOpt = converterFactory.getConverter(responseContentType, acceptType);
                if (converterOpt.isEmpty()) {
                    return super.writeWith(body);
                }

                PayloadConverter converter = converterOpt.get();
                Flux<DataBuffer> bodyFlux = Flux.from(body);

                return bodyFlux
                        .collectList()
                        .flatMap(buffers -> {
                            DataBuffer combined = bufferFactory.join(buffers);
                            return converter.convert(combined, responseContentType, acceptType);
                        })
                        .flatMap(converted -> {
                            HttpHeaders headers = getHeaders();
                            headers.setContentType(acceptType);
                            return super.writeWith(Flux.just(converted));
                        });
            }
        };
    }

    @Override
    public int getOrder() {
        return -20;
    }
}
