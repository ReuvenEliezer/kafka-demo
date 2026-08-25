package com.reuven.kafka.demo.copy.provider;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.exception.DisallowedProviderHostException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF defence for download targets (FR-062, research.md R13). Checked on the initial URL and again
 * on every redirect hop — {@code followRedirects(NEVER)} in {@link HttpProviderClient} is what makes
 * per-hop checking possible, since an allowlisted host that redirects inward would otherwise defeat
 * this check entirely.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class ProviderHostAllowlist {

    private static final String HTTPS = "https";
    private static final String HTTP = "http";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    private final CopyProperties properties;

    public boolean isAllowed(URI uri) {
        if (uri.getHost() == null || !schemeAllowed(uri)) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return properties.provider().allowedHosts().stream()
                .map(allowed -> allowed.toLowerCase(Locale.ROOT))
                .anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
    }

    /**
     * https is required for every real host. Plain http is accepted only for loopback addresses —
     * research.md R20 commits the provider test fixture to {@code com.sun.net.httpserver.HttpServer}
     * (not {@code HttpsServer}), so local/test traffic to {@code localhost} needs a path that doesn't
     * weaken the production posture: a non-loopback host can never satisfy this exemption regardless
     * of what an attacker puts in a redirect Location header.
     */
    private static boolean schemeAllowed(URI uri) {
        if (HTTPS.equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        return HTTP.equalsIgnoreCase(uri.getScheme()) && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    /**
     * @throws DisallowedProviderHostException when {@code uri} fails {@link #isAllowed}
     */
    public void checkOrThrow(URI uri) {
        if (!isAllowed(uri)) {
            throw new DisallowedProviderHostException(
                    "Download URL host is not allowlisted: %s (scheme=%s)".formatted(uri.getHost(), uri.getScheme()));
        }
    }
}
