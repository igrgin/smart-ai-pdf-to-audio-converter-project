package dev.audiobook.platform.generation;

public interface CanonicalSpeechDecoder {

    byte[] decode(byte[] providerAudio);
}
