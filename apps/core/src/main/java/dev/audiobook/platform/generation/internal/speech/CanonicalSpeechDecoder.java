package dev.audiobook.platform.generation.internal.speech;

public interface CanonicalSpeechDecoder {

    byte[] decode(byte[] providerAudio);
}
