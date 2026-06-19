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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.enterprise.gateway.transform.wrapper.UnifiedResponseWrapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResponseWrapperFilter implements GlobalFilter, Ordered {

    private final UnifiedResponseWrapper responseWrapper;
    private final DataBufferFactory bufferFactory = new NettyDataBufferFactory(io.netty.buffer.PooledByteBufAllocator.DEFAULT);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        ServerHttpResponse mutatedResponse = wrapResponse(response, exchange);

        ServerWebExchange mutatedExchange = exchange.mutate()
                .response(mutatedResponse)
                .build();

        return chain.filter(mutatedExchange);
    }

    private ServerHttpResponse wrapResponse(ServerHttpResponse response, ServerWebExchange exchange) {
        return new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                HttpStatus status = getStatusCode();
                if (status == null) {
                    status = HttpStatus.OK;
                }

                Flux<DataBuffer> bodyFlux = Flux.from(body);

                return bodyFlux
                        .collectList()
                        .flatMap(buffers -> {
                            if (buffers.isEmpty()) {
                                DataBuffer emptyBuffer = bufferFactory.allocateBuffer(0);
                                return responseWrapper.wrap(emptyBuffer, status);
                            }
                            DataBuffer combined = bufferFactory.join(buffers);
                            return responseWrapper.wrap(combined, status);
                        })
                        .flatMap(wrappedBuffer -> {
                            HttpHeaders headers = getHeaders();
                            headers.setContentType(MediaType.APPLICATION_JSON);
                            return super.writeWith(Flux.just(wrappedBuffer));
                        })
                        .doOnError(e -> log.error("Response wrapping failed", e));
            }
        };
    }

    @Override
    public int getOrder() {
        return -10;
    }
}
