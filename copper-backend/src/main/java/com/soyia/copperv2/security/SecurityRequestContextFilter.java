package com.soyia.copperv2.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityRequestContextFilter extends OncePerRequestFilter {
    public static final String TRACE_HEADER = "X-Copper-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        SecurityRequestContext.Metadata metadata = SecurityRequestContext.begin(request);
        response.setHeader(TRACE_HEADER, metadata.traceId());
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityRequestContext.clear();
        }
    }
}
