package dev.squire.api;

import dev.squire.gateway.GatewayService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class GatewayController {
    private final GatewayService gateway;

    public GatewayController(GatewayService gateway) {
        this.gateway = gateway;
    }

    @PostMapping(value = "/v1/responses", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> responses(@RequestBody byte[] body, ServerWebExchange exchange) {
        return gateway.forward(body, exchange);
    }
}
