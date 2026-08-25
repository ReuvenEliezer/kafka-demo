package com.reuven.kafka.demo.copy.ingress;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

/**
 * Wraps the notification path's request so the raw bytes are available for signature verification
 * before any JSON parsing (FR-070, research.md R10) — the signature covers the raw request bytes,
 * and verifying a Jackson re-serialisation breaks on any formatting difference the provider's JSON
 * writer happens to use.
 *
 * <p>Bounded by {@code copy.notification.max-body-size}: bytes beyond that limit are not cached,
 * which in practice makes the signature check fail closed on an oversized body rather than buffering
 * it without limit.
 */
@Component
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class RawBodyCachingFilter extends OncePerRequestFilter {

    private final CopyProperties properties;

    public RawBodyCachingFilter(CopyProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.notification().path().equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper wrapped =
                new ContentCachingRequestWrapper(request, (int) properties.notification().maxBodySize().toBytes());
        wrapped.getInputStream().readAllBytes();
        chain.doFilter(wrapped, response);
    }
}
