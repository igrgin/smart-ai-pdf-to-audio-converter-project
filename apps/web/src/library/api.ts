import type { NarrationPlan } from "../narration-review";
import { SameOriginResponseError, type CsrfProof } from "../session";

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
  version: number;
  manifestUrl: string;
}

export interface DeletionReceipt {
  requestId: string;
  scope: "AUDIOBOOK" | "ACCOUNT";
  state: "ACCEPTED" | "ERASING" | "LIVE_ERASED" | "COMPLETED" | "FAILED";
  requestedAt: string;
  quickErasureDueAt: string;
  liveErasureDueAt: string;
  providerEvidenceDueAt: string;
  backupExpiresAt: string;
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

export async function fetchLibrary(signal: AbortSignal): Promise<Library> {
  const response = await fetch("/api/v1/library", {
    headers: { Accept: "application/json" },
    signal
  });
  if (!response.ok) throw new SameOriginResponseError(response.status, "Library");
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

export async function deletePrivateAudiobook(
  audiobookId: string,
  version: number,
  csrf: CsrfProof
): Promise<DeletionReceipt> {
  const response = await fetch(`/api/v1/audiobooks/${audiobookId}`, {
    method: "DELETE",
    headers: {
      Accept: "application/json",
      "Idempotency-Key": crypto.randomUUID(),
      "If-Match": `"${version}"`,
      [csrf.headerName]: csrf.token
    }
  });
  if (!response.ok) throw new SameOriginResponseError(response.status, "Audiobook deletion");
  return response.json() as Promise<DeletionReceipt>;
}

export async function deleteListenerAccount(csrf: CsrfProof): Promise<DeletionReceipt> {
  const response = await fetch("/api/v1/account", {
    method: "DELETE",
    headers: {
      Accept: "application/json",
      "Idempotency-Key": crypto.randomUUID(),
      [csrf.headerName]: csrf.token
    }
  });
  if (!response.ok) throw new SameOriginResponseError(response.status, "Account deletion");
  return response.json() as Promise<DeletionReceipt>;
}
