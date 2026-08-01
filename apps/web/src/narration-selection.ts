import type { CsrfProof } from "./identity-session";

export type VoiceAvailability = "AVAILABLE" | "TEMPORARILY_UNAVAILABLE" | "RETIRED";
export type NarrationPace = "MEASURED" | "NATURAL" | "BRISK";

export interface NarratorVoice {
  id: string;
  displayName: string;
  englishVariety: string;
  descriptors: string[];
  descriptorReviewVersion: string;
  availability: VoiceAvailability;
  preview: {
    uri: string;
    passageVersion: string;
    durationSeconds: number;
    aiGenerated: boolean;
  };
}

export interface VoiceCatalog {
  voices: NarratorVoice[];
  paces: NarrationPace[];
  defaultPace: NarrationPace;
}

export interface ConfirmedGenerationRecipe {
  recipeId: string;
  conversionId: string;
  voiceId: string;
  voiceDisplayName: string;
  pace: NarrationPace;
  recipeDigest: string;
  conversionVersion: number;
}

export async function fetchVoiceCatalog(signal: AbortSignal): Promise<VoiceCatalog> {
  const response = await fetch("/api/v1/narrator-voices", {
    headers: { Accept: "application/json" },
    signal
  });
  return responseJson<VoiceCatalog>(response, "read the Narrator Voice catalog");
}

export async function confirmGenerationRecipe(
  conversionId: string,
  voiceId: string,
  pace: NarrationPace,
  csrf: CsrfProof,
  expectedConversionVersion = 0
): Promise<ConfirmedGenerationRecipe> {
  const response = await fetch(`/api/v1/audiobook-conversions/${conversionId}/generation-recipe`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID(),
      "If-Match": `"${expectedConversionVersion}"`,
      [csrf.headerName]: csrf.token
    },
    body: JSON.stringify({ voiceId, pace })
  });
  return responseJson<ConfirmedGenerationRecipe>(response, "confirm the Generation Recipe");
}

async function responseJson<T>(response: Response, action: string): Promise<T> {
  if (!response.ok) throw new Error(`Unable to ${action} (${response.status})`);
  return response.json() as Promise<T>;
}
