package com.parking.platform.gateway.filter;

import com.parking.platform.common.constant.Constants;
import com.parking.platform.common.context.RequestContext;
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
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        RequestContext context = RequestContext.create();

        String existingRequestId = httpRequest.getHeader(Constants.HEADER_REQUEST_ID);
        if (existingRequestId != null && !existingRequestId.isEmpty()) {
            context.setAttribute("originalRequestId", existingRequestId);
        }

        httpResponse.setHeader(Constants.HEADER_REQUEST_ID, context.getRequestId());
        httpResponse.setHeader(Constants.HEADER_TRACE_ID, context.getTraceId());

        try {
            log.debug("Processing request {}: {} {}",
                    context.getRequestId(),
                    httpRequest.getMethod(),
                    httpRequest.getRequestURI());

            chain.doFilter(request, response);

        } finally {
            long elapsed = context.getElapsedMillis();
            log.debug("Request {} completed in {}ms with status {}",
                    context.getRequestId(),
                    elapsed,
                    httpResponse.getStatus());

            RequestContext.clear();
        }
    }
}
