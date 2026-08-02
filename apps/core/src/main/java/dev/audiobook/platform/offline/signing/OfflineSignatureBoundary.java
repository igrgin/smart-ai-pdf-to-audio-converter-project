package dev.audiobook.platform.offline.signing;

interface OfflineSignatureBoundary {

    String publicKey();

    byte[] sign(byte[] payload);
}
