package com.causal.eventstore.exception;

public class BatchSizeExceededException extends RuntimeException {
    private final int actualSize;
    private final int maxSize;

    public BatchSizeExceededException(int actualSize, int maxSize) {
        super(String.format("Batch size %d exceeds maximum allowed size %d", actualSize, maxSize));
        this.actualSize = actualSize;
        this.maxSize = maxSize;
    }

    public int getActualSize() {
        return actualSize;
    }

    public int getMaxSize() {
        return maxSize;
    }
}
