package com.parking.platform.gateway.filter;

import com.parking.platform.common.exception.RateLimitExceededException;
import com.parking.platform.gateway.config.RateLimitConfig;
import com.parking.platform.gateway.service.RateLimitService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitService rateLimitService;
    private final RateLimitConfig rateLimitConfig;

    public RateLimitFilter(RateLimitService rateLimitService, RateLimitConfig rateLimitConfig) {
        this.rateLimitService = rateLimitService;
        this.rateLimitConfig = rateLimitConfig;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!rateLimitConfig.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String limitKey = resolveLimitKey(httpRequest);

        if (!rateLimitService.tryAcquire(limitKey)) {
            log.warn("Rate limit exceeded for key: {}", limitKey);

            RateLimitService.RateLimitInfo info = rateLimitService.getRateLimitInfo(limitKey);
            httpResponse.setStatus(429);
            httpResponse.setHeader("X-RateLimit-Limit", String.valueOf(info.minuteLimit()));
            httpResponse.setHeader("X-RateLimit-Remaining", String.valueOf(info.minuteRemaining()));
            httpResponse.setHeader("Retry-After", "60");
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write(
                    "{\"code\":429,\"message\":\"Rate limit exceeded. Please try again later\"}"
            );
            return;
        }

        addRateLimitHeaders(httpResponse, limitKey);
        chain.doFilter(request, response);
    }

    private String resolveLimitKey(HttpServletRequest request) {
        if (rateLimitConfig.getHeaderBased().isEnabled()) {
            String userId = request.getHeader("X-User-ID");
            String apiKey = request.getHeader("X-API-Key");
            if (userId != null && !userId.isEmpty()) {
                return "user:" + userId;
            }
            if (apiKey != null && !apiKey.isEmpty()) {
                return "apikey:" + apiKey;
            }
        }

        if (rateLimitConfig.getIpBased().isEnabled()) {
            String clientIp = getClientIp(request);
            if (clientIp != null && !clientIp.isEmpty()) {
                return "ip:" + clientIp;
            }
        }

        return "global";
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader != null) {
            return xfHeader.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void addRateLimitHeaders(HttpServletResponse response, String key) {
        RateLimitService.RateLimitInfo info = rateLimitService.getRateLimitInfo(key);
        response.setHeader("X-RateLimit-Limit", String.valueOf(info.minuteLimit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(info.minuteRemaining()));
        response.setHeader("X-RateLimit-HourLimit", String.valueOf(info.hourLimit()));
        response.setHeader("X-RateLimit-HourRemaining", String.valueOf(info.hourRemaining()));
    }
}
