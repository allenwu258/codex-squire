package dev.squire.api;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.PayloadTooLargeException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrors.class);

    public record ErrorDetail(String code, String message) { }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, ErrorDetail>> domain(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("error",
                new ErrorDetail(exception.code(), exception.getMessage())));
    }

    @ExceptionHandler({WebExchangeBindException.class, ServerWebInputException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, ErrorDetail>> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error",
                new ErrorDetail("INVALID_REQUEST", "Request fields or JSON are invalid")));
    }

    @ExceptionHandler({DataBufferLimitException.class, PayloadTooLargeException.class})
    public ResponseEntity<Map<String, ErrorDetail>> oversized(Exception exception) {
        return ResponseEntity.status(413).body(Map.of("error",
                new ErrorDetail("REQUEST_TOO_LARGE", "Request body exceeds the 8 MiB limit")));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, ErrorDetail>> http(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("error",
                new ErrorDetail("HTTP_" + exception.getStatusCode().value(), "HTTP request rejected")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, ErrorDetail>> unexpected(Exception exception) {
        LOGGER.error("Unhandled request failure: {}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("error",
                new ErrorDetail("INTERNAL_ERROR", "Operation failed; inspect the local backend log")));
    }
}
