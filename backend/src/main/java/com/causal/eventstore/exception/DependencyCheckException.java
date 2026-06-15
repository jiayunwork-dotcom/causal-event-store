package com.causal.eventstore.exception;

import java.util.List;

public class DependencyCheckException extends RuntimeException {
    private final List<String> missingEventIds;

    public DependencyCheckException(String message, List<String> missingEventIds) {
        super(message);
        this.missingEventIds = missingEventIds;
    }

    public List<String> getMissingEventIds() {
        return missingEventIds;
    }
}
