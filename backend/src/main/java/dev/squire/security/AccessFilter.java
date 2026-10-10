package dev.squire.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AccessFilter implements WebFilter {
    private final LocalSecrets secrets;
    private final Set<String> origins;
    private final boolean allowAllOrigins;

    public AccessFilter(LocalSecrets secrets, @Value("${squire.allowed-origins}") String allowedOrigins) {
        this.secrets = secrets;
        String configured = allowedOrigins == null ? "" : allowedOrigins.trim();
        allowAllOrigins = "*".equals(configured);
        origins = Arrays.stream(configured.split(",")).map(String::trim).filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean admin = path.startsWith("/api/");
        String origin = exchange.getRequest().getHeaders().getOrigin();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        if (origin != null) {
            if (!allowAllOrigins && !origins.contains(origin)) {
                return reject(exchange, HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
            }
            headers.setAccessControlAllowOrigin(origin);
            headers.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
            headers.setAccessControlAllowMethods(java.util.List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE));
            headers.setAccessControlAllowHeaders(java.util.List.of("Authorization", "Content-Type"));
        }
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS && origin != null) {
            exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return exchange.getResponse().setComplete();
        }
        if (path.equals("/api/health") && exchange.getRequest().getMethod() == HttpMethod.GET) {
            return chain.filter(exchange);
        }
        if (!secrets.isAuthorized(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION), admin)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = ("{\"error\":{\"code\":\"" + code + "\",\"message\":\"Access denied\"}}")
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
