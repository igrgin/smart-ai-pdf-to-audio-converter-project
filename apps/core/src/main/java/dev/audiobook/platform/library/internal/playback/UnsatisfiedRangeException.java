package dev.audiobook.platform.library.internal.playback;

public final class UnsatisfiedRangeException extends RuntimeException {

    private final long completeLength;

    public UnsatisfiedRangeException(long completeLength) {
        this.completeLength = completeLength;
    }

    long completeLength() {
        return completeLength;
    }
}
