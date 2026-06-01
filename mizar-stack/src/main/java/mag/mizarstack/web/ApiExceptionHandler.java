package mag.mizarstack.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "error", "BAD_REQUEST",
                "message", ex.getMessage() == null ? "Invalid request." : ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "error", ex.getStatusCode().toString(),
                "message", ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason()
        );
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception ex) {
        if (isClientDisconnected(ex)) {
            log.debug("Client disconnected during response write: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }

        log.error("Unhandled exception", ex);

        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "error", "INTERNAL_SERVER_ERROR",
                "message", ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private static boolean isClientDisconnected(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String className = current.getClass().getName();
            if (className.contains("ClientAbortException")) {
                return true;
            }

            if (current instanceof IOException) {
                String message = current.getMessage();
                if (message != null) {
                    String normalized = message.toLowerCase();
                    if (normalized.contains("broken pipe")
                            || normalized.contains("connection reset by peer")
                            || normalized.contains("forcibly closed by the remote host")) {
                        return true;
                    }
                }
            }

            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("broken pipe")
                        || normalized.contains("connection reset by peer")
                        || normalized.contains("forcibly closed by the remote host")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
