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
  state: "PREPARING" | "PAUSED" | "AWAITING_REVIEW" | "GENERATING" | "FINALIZING" | "FINALIZED" | "FAILED" | "CANCELLED";
  reasonCode: "EXTRACTION_PENDING" | "NARRATION_PLAN_PENDING" | "NARRATION_PLAN_REQUIRES_INTERVENTION" | "SOURCE_TOO_DAMAGED" | "PROVIDER_RESULT_AMBIGUOUS" | "NARRATION_REVIEW_AVAILABLE" | "NARRATION_REVIEW_APPROVED" | "NARRATION_RECOMMENDATIONS_ACCEPTED" | "GENERATION_IN_PROGRESS" | "FINAL_AUDIOBOOK_VALIDATION" | "PRIVATE_AUDIOBOOK_AVAILABLE" | "CONVERSION_FAILED" | "LISTENER_CANCELLED";
  allowedActions: AllowedAction[];
  version: number;
  recovery?: { resumeFromPage: number; listenerGuidance: string };
  recipeId?: string;
  voiceId?: string;
  voiceDisplayName?: string;
  pace?: "MEASURED" | "NATURAL" | "BRISK";
  explicitNarrationChoiceRequired: boolean;
  privateAudiobook?: PrivateAudiobookSummary;
}

export interface PrivateAudiobookSummary {
  audiobookId: string;
  assetVersionId: string;
  availability: "AVAILABLE" | "RIGHTS_QUARANTINED" | "TECHNICALLY_UNAVAILABLE" | "DELETING" | "ERASED";
  totalDurationMs: number;
  manifestUrl: string;
}

export type AllowedAction = "REVIEW_NARRATION_PLAN" | "ACCEPT_RECOMMENDATIONS" | "RETRY_NARRATION_PLAN";

export interface ConversionProgress extends AudiobookConversion {
  narrationPlan?: NarrationPlan;
  pause?: {
    reasonCode: string;
    responsibleParty: "LISTENER" | "PLATFORM" | "PROVIDER" | "OPERATOR";
    safeResumeStage: "INSPECTION" | "EXTRACTION" | "NARRATION_ANALYSIS" | "SPEECH" | "ASSEMBLY" | "PACKAGING" | "FINALIZATION";
    deadline?: string;
  };
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
  sourceIndex: number;
  sourceUnit: string;
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

export async function retryNarrationPlan(
  conversionId: string,
  version: number,
  csrf: CsrfProof
): Promise<ConversionProgress> {
  const response = await fetch(
    `/api/v1/audiobook-conversions/${conversionId}/narration-plan-recovery`,
    {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Idempotency-Key": crypto.randomUUID(),
        "If-Match": `"${version}"`,
        [csrf.headerName]: csrf.token
      }
    }
  );
  if (!response.ok) throw new Error(`Narration Plan recovery returned ${response.status}`);
  return response.json() as Promise<ConversionProgress>;
}
