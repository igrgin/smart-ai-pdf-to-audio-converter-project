package dev.audiobook.platform.library;

final class UnsatisfiedRangeException extends RuntimeException {

    private final long completeLength;

    UnsatisfiedRangeException(long completeLength) {
        this.completeLength = completeLength;
    }

    long completeLength() {
        return completeLength;
    }
}
