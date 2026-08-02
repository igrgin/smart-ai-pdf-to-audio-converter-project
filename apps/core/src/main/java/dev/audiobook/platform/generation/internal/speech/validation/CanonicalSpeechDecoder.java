package dev.audiobook.platform.generation.internal.speech.validation;

public interface CanonicalSpeechDecoder {

    byte[] decode(byte[] providerAudio);
}
