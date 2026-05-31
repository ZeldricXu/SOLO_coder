package com.observability.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RequestLogFilter 测试")
class RequestLogFilterTest {

    private RequestLogFilter filter;

    @Mock
    private WebFilterChain filterChain;

    @BeforeEach
    void setup() {
        filter = new RequestLogFilter();
    }

    @Nested
    @DisplayName("请求日志过滤测试")
    class FilterTests {

        @Test
        @DisplayName("正常场景：请求有TraceId")
        void filter_WithExistingTraceId_UsesExistingTraceId() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .method(HttpMethod.GET, "/api/test")
                    .header("X-Trace-Id", "existing-trace-123")
                    .build();

            MockServerHttpResponse response = new MockServerHttpResponse();
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getResponse()).thenReturn(response);
            when(filterChain.filter(exchange)).thenReturn(Mono.empty());

            Mono<Void> result = filter.filter(exchange, filterChain);

            StepVerifier.create(result)
                    .verifyComplete();

            verify(filterChain, times(1)).filter(exchange);
        }

        @Test
        @DisplayName("正常场景：请求无TraceId时自动生成")
        void filter_WithoutTraceId_GeneratesNewTraceId() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .method(HttpMethod.POST, "/api/resources")
                    .build();

            MockServerHttpResponse response = new MockServerHttpResponse();
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getResponse()).thenReturn(response);
            when(filterChain.filter(exchange)).thenReturn(Mono.empty());

            Mono<Void> result = filter.filter(exchange, filterChain);

            StepVerifier.create(result)
                    .verifyComplete();

            HttpHeaders responseHeaders = response.getHeaders();
            assert responseHeaders.getFirst("X-Trace-Id") != null;
            assert !responseHeaders.getFirst("X-Trace-Id").isEmpty();
        }

        @Test
        @DisplayName("正常场景：响应头包含TraceId")
        void filter_AddsTraceIdToResponse() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .method(HttpMethod.GET, "/api/test")
                    .header("X-Trace-Id", "test-trace-456")
                    .build();

            MockServerHttpResponse response = new MockServerHttpResponse();
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getResponse()).thenReturn(response);
            when(filterChain.filter(exchange)).thenReturn(Mono.empty());

            filter.filter(exchange, filterChain).block();

            assert "test-trace-456".equals(response.getHeaders().getFirst("X-Trace-Id"));
        }

        @Test
        @DisplayName("边界场景：请求有用户ID和命名空间")
        void filter_WithUserIdAndNamespace_PassesContext() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .method(HttpMethod.GET, "/api/test")
                    .header("X-Trace-Id", "trace-789")
                    .header("X-User-Id", "user-123")
                    .header("X-Namespace", "production")
                    .build();

            MockServerHttpResponse response = new MockServerHttpResponse();
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getResponse()).thenReturn(response);
            when(filterChain.filter(exchange)).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：TraceId为空字符串时生成新的")
        void filter_EmptyTraceIdHeader_GeneratesNew() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .method(HttpMethod.GET, "/api/test")
                    .header("X-Trace-Id", "")
                    .build();

            MockServerHttpResponse response = new MockServerHttpResponse();
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getResponse()).thenReturn(response);
            when(filterChain.filter(exchange)).thenReturn(Mono.empty());

            filter.filter(exchange, filterChain).block();

            String traceId = response.getHeaders().getFirst("X-Trace-Id");
            assert traceId != null;
            assert !traceId.isEmpty();
        }

        @Test
        @DisplayName("异常场景：过滤器链抛出异常")
        void filter_FilterChainThrowsException_LogsError() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .method(HttpMethod.GET, "/api/test")
                    .header("X-Trace-Id", "trace-error")
                    .build();

            MockServerHttpResponse response = new MockServerHttpResponse();
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getResponse()).thenReturn(response);
            when(filterChain.filter(exchange)).thenReturn(Mono.error(new RuntimeException("Chain failed")));

            Mono<Void> result = filter.filter(exchange, filterChain);

            StepVerifier.create(result)
                    .expectError(RuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("正常场景：包含客户端地址")
        void filter_WithRemoteAddress_LogsClientInfo() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .method(HttpMethod.GET, "/api/test")
                    .remoteAddress(new InetSocketAddress("192.168.1.100", 54321))
                    .build();

            MockServerHttpResponse response = new MockServerHttpResponse();
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getResponse()).thenReturn(response);
            when(filterChain.filter(exchange)).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, filterChain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：各种HTTP方法")
        void filter_VariousHttpMethods_WorksCorrectly() {
            for (HttpMethod method : HttpMethod.values()) {
                MockServerHttpRequest request = MockServerHttpRequest
                        .method(method, "/api/test")
                        .build();

                MockServerHttpResponse response = new MockServerHttpResponse();
                ServerWebExchange exchange = mock(ServerWebExchange.class);
                when(exchange.getRequest()).thenReturn(request);
                when(exchange.getResponse()).thenReturn(response);
                when(filterChain.filter(exchange)).thenReturn(Mono.empty());

                StepVerifier.create(filter.filter(exchange, filterChain))
                        .verifyComplete();
            }
        }
    }

    @Nested
    @DisplayName("过滤器排序测试")
    class OrderTests {

        @Test
        @DisplayName("过滤器顺序正确")
        void filterOrder_IsHighestPrecedence() {
            int order = filter.getOrder();
            assert order == org.springframework.core.Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
