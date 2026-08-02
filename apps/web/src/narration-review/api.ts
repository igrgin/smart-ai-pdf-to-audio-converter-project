import type { CsrfProof } from "../session";

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

export type NarrationReviewAction = "APPROVE" | "SKIP_OPTIONAL";
export type NarrationTreatment = "OMIT" | "READ_VERBATIM" | "SUMMARIZE" | "DESCRIBE";

export interface NarrationReviewItemDecision {
  sourceChapterOrdinal: number;
  ordinal: number;
  treatment: NarrationTreatment;
  narrationSnippet?: string;
}

export interface NarrationSectionDecision {
  clientId: string;
  title: string;
  excluded: boolean;
  sourceChapterOrdinals: number[];
  reviewItems: NarrationReviewItemDecision[];
}

export interface NarrationReviewResult {
  decisionId: string;
  action: NarrationReviewAction;
  conversionVersion: number;
}

export class NarrationReviewProblem extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly recoverable: boolean,
    readonly currentVersion?: number
  ) {
    super(message);
  }
}

export async function submitNarrationReview(
  conversionId: string,
  version: number,
  action: NarrationReviewAction,
  sections: NarrationSectionDecision[],
  csrf: CsrfProof,
  operationKey: string
): Promise<NarrationReviewResult> {
  const body = boundedReviewRequest(action, sections);
  const response = await fetch(`/api/v1/audiobook-conversions/${conversionId}/narration-review`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "Idempotency-Key": operationKey,
      "If-Match": `"${version}"`,
      [csrf.headerName]: csrf.token
    },
    body: JSON.stringify(body)
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({})) as {
      code?: string;
      detail?: string;
      recoverable?: boolean;
      currentVersion?: number;
    };
    throw new NarrationReviewProblem(
      problem.code ?? "NARRATION_REVIEW_FAILED",
      problem.detail ?? "The Narration Review could not be saved. Try again.",
      problem.recoverable === true,
      problem.currentVersion
    );
  }
  return response.json() as Promise<NarrationReviewResult>;
}

export function narrationReviewRequestFingerprint(
  version: number,
  action: NarrationReviewAction,
  sections: NarrationSectionDecision[]
): string {
  return JSON.stringify({ version, ...boundedReviewRequest(action, sections) });
}

function boundedReviewRequest(action: NarrationReviewAction, sections: NarrationSectionDecision[]) {
  const boundedSections = sections.map((section) => ({
    clientId: section.clientId,
    title: section.title,
    excluded: section.excluded,
    sourceChapterOrdinals: [...section.sourceChapterOrdinals],
    reviewItems: section.reviewItems.map((item) => ({
      sourceChapterOrdinal: item.sourceChapterOrdinal,
      ordinal: item.ordinal,
      treatment: item.treatment,
      narrationSnippet: item.narrationSnippet
    }))
  }));
  return { action, sections: action === "APPROVE" ? boundedSections : [] };
}
