package dev.audiobook.platform.offline.internal;

interface OfflineSignatureBoundary {

    String publicKey();

    byte[] sign(byte[] payload);
}
