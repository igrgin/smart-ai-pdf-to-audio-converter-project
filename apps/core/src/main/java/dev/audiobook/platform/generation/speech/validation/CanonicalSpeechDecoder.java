package dev.audiobook.platform.generation.speech.validation;

import dev.audiobook.platform.generation.speech.validation.service.*;

public interface CanonicalSpeechDecoder {

    byte[] decode(byte[] providerAudio);
}
