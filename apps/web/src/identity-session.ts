export interface CsrfProof {
  headerName: string;
  parameterName: string;
  token: string;
}

export interface ListenerSummary {
  displayName: string;
  contactEmail?: string;
  signInMethods: string[];
}

export interface IdentitySession {
  authenticated: boolean;
  listener?: ListenerSummary;
  csrf: CsrfProof;
}

export interface Library {
  displayName: string;
  contactEmail?: string;
  signInMethods: string[];
  audiobooks: AudiobookConversion[];
  conversionEntitlement: ConversionEntitlement;
}

export interface AudiobookConversion {
  conversionId: string;
  state: "PREPARING" | "GENERATING";
  version: number;
  recipeId?: string;
  voiceId?: string;
  voiceDisplayName?: string;
  pace?: "MEASURED" | "NATURAL" | "BRISK";
  explicitNarrationChoiceRequired: boolean;
}

export interface ConversionEntitlement {
  status: "NO_GRANT" | "AVAILABLE" | "EXHAUSTED" | "EXPIRED";
  grantedCharacters: number;
  availableCharacters: number;
  reservedCharacters: number;
  committedCharacters: number;
  canStartConversion: boolean;
  denialReason?: string;
}

export async function fetchIdentitySession(signal: AbortSignal): Promise<IdentitySession> {
  const response = await fetch("/api/v1/auth/session", {
    headers: { Accept: "application/json" },
    signal
  });
  if (!response.ok) throw new Error(`Identity session returned ${response.status}`);
  return response.json() as Promise<IdentitySession>;
}

export async function fetchLibrary(signal: AbortSignal): Promise<Library> {
  const response = await fetch("/api/v1/library", {
    headers: { Accept: "application/json" },
    signal
  });
  if (!response.ok) throw new Error(`Library returned ${response.status}`);
  return response.json() as Promise<Library>;
}
