package com.causal.eventstore.config;

import com.causal.eventstore.exception.BatchSizeExceededException;
import com.causal.eventstore.exception.DependencyCheckException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(DependencyCheckException.class)
    public ResponseEntity<?> handleDependencyCheck(DependencyCheckException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("error", "DEPENDENCY_CHECK_FAILED");
        body.put("message", e.getMessage());
        body.put("missingEventIds", e.getMissingEventIds());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(BatchSizeExceededException.class)
    public ResponseEntity<?> handleBatchSize(BatchSizeExceededException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("error", "BATCH_SIZE_EXCEEDED");
        body.put("message", e.getMessage());
        body.put("actualSize", e.getActualSize());
        body.put("maxSize", e.getMaxSize());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("error", "ILLEGAL_STATE");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArg(IllegalArgumentException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("error", "BAD_REQUEST");
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneric(Exception e) {
        log.error("Unhandled exception", e);
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("error", "INTERNAL_ERROR");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
