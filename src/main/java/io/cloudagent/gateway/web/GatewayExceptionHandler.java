package io.cloudagent.gateway.web;

import io.cloudagent.gateway.copilot.CopilotConnectionException;
import io.cloudagent.gateway.docker.DockerException;
import io.cloudagent.gateway.session.SessionException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates gateway-internal exceptions into simple JSON error responses. */
@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({DockerException.class, CopilotConnectionException.class, SessionException.class})
    public ResponseEntity<Map<String, String>> handleGatewayError(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
