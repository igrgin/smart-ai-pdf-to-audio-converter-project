package dev.audiobook.platform.offline.internal.signing;

interface OfflineSignatureBoundary {

    String publicKey();

    byte[] sign(byte[] payload);
}
