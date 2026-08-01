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
  state: "PREPARING" | "AWAITING_REVIEW" | "GENERATING";
  reasonCode: "EXTRACTION_PENDING" | "NARRATION_PLAN_PENDING" | "NARRATION_PLAN_REQUIRES_INTERVENTION" | "NARRATION_REVIEW_AVAILABLE" | "GENERATION_IN_PROGRESS";
  allowedActions: AllowedAction[];
  version: number;
  recipeId?: string;
  voiceId?: string;
  voiceDisplayName?: string;
  pace?: "MEASURED" | "NATURAL" | "BRISK";
  explicitNarrationChoiceRequired: boolean;
}

export type AllowedAction = "REVIEW_NARRATION_PLAN" | "ACCEPT_RECOMMENDATIONS";

export interface ConversionProgress extends AudiobookConversion {
  narrationPlan?: NarrationPlan;
}

export interface NarrationPlan {
  normalProseEditable: false;
  chapters: NarrationChapter[];
}

export interface NarrationChapter {
  ordinal: number;
  title?: string;
  provenance: SourceProvenance;
  gaps: Array<{ sourceUnit: string; reasonCode: string }>;
  reviewItems: NarrationReviewItem[];
}

export interface SourceProvenance {
  source: string;
  spineIndex: number;
  spineItem: string;
  anchor?: string;
  sourceDeclared: boolean;
  confidence: number;
}

export interface NarrationReviewItem {
  ordinal: number;
  sourceOrdinal: number;
  type: string;
  provenance: SourceProvenance;
  extractionConfidence: number;
  classificationConfidence: number;
  treatmentConfidence: number;
  recommendedTreatment: "OMIT" | "READ_VERBATIM" | "SUMMARIZE" | "DESCRIBE";
  narrationSnippet?: string;
  reasonCode: string;
}

export type ConversionPollResult =
  | { notModified: true; entityTag?: string }
  | { notModified: false; entityTag?: string; progress: ConversionProgress };

export interface ConversionEntitlement {
  status: "NO_GRANT" | "AVAILABLE" | "EXHAUSTED" | "EXPIRED";
  grantedCharacters: number;
  availableCharacters: number;
  reservedCharacters: number;
  committedCharacters: number;
  canStartConversion: boolean;
  denialReason?: string;
  source?: "NONE" | "FREE" | "DEMONSTRATION_SUBSCRIPTION";
  demonstrationSubscriptionStatus?: "ACTIVE" | "CANCEL_AT_PERIOD_END" | "CANCELED" | "PAST_DUE" | "UNPAID";
  demonstrationOnly?: boolean;
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

export async function fetchConversionProgress(
  conversionId: string,
  entityTag: string | undefined,
  signal: AbortSignal
): Promise<ConversionPollResult> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (entityTag) headers["If-None-Match"] = entityTag;
  const response = await fetch(`/api/v1/audiobook-conversions/${conversionId}`, { headers, signal });
  const nextEntityTag = response.headers.get("ETag") ?? entityTag;
  if (response.status === 304) {
    return { notModified: true, entityTag: nextEntityTag };
  }
  if (!response.ok) throw new Error(`Audiobook Conversion returned ${response.status}`);
  return {
    notModified: false,
    entityTag: nextEntityTag,
    progress: await response.json() as ConversionProgress
  };
}
