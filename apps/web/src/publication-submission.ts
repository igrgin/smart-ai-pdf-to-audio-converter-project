import type { CsrfProof } from "./identity-session";

export type SubmissionState =
  | "AWAITING_UPLOAD"
  | "UPLOADED"
  | "INSPECTING"
  | "ADMITTED"
  | "REJECTED"
  | "EXPIRED"
  | "CANCELLED";

interface Creation {
  submissionId: string;
  state: SubmissionState;
  uploadSession: {
    endpoint: string;
    token: string;
    chunkSize: number;
  };
}

interface UploadProgress {
  nextOffset: number;
  complete: boolean;
  storageGeneration?: string;
}

export interface Submission {
  submissionId: string;
  state: SubmissionState;
  reasonCode?: string;
  conversionId?: string;
}

export type TransferStage = "HASHING" | "UPLOADING" | "INSPECTING";

export async function submitAuthorizedEpub(
  file: File,
  csrf: CsrfProof,
  onStage: (stage: TransferStage) => void
): Promise<Submission> {
  onStage("HASHING");
  const publicationDigest = await sha256(file);
  const createResponse = await fetch("/api/v1/publication-submissions", {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID(),
      [csrf.headerName]: csrf.token
    },
    body: JSON.stringify({
      mediaType: "application/epub+zip",
      byteLength: file.size,
      sha256: publicationDigest,
      rightsAttestation: { termsVersion: "rights-v1", noticeVersion: "notice-v1" }
    })
  });
  const creation = await responseJson<Creation>(createResponse, "create publication submission");

  onStage("UPLOADING");
  let offset = 0;
  let generation: string | undefined;
  while (offset < file.size) {
    const chunk = file.slice(offset, Math.min(offset + creation.uploadSession.chunkSize, file.size));
    const chunkDigest = await sha256(chunk);
    const uploadResponse = await fetch(creation.uploadSession.endpoint, {
      method: "PUT",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/octet-stream",
        "Upload-Token": creation.uploadSession.token,
        "Upload-Offset": String(offset),
        "Upload-Length": String(file.size),
        "Upload-Chunk-SHA256": chunkDigest
      },
      body: chunk
    });
    const progress = await responseJson<UploadProgress>(uploadResponse, "transfer publication chunk");
    if (progress.nextOffset <= offset || progress.nextOffset > file.size) {
      throw new Error("Upload session returned an invalid offset");
    }
    offset = progress.nextOffset;
    generation = progress.storageGeneration ?? generation;
  }
  if (!generation) throw new Error("Upload session did not return a storage generation");

  const confirmResponse = await fetch(`/api/v1/publication-submissions/${creation.submissionId}/confirm`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID(),
      [csrf.headerName]: csrf.token
    },
    body: JSON.stringify({
      storageGeneration: generation,
      byteLength: file.size,
      sha256: publicationDigest
    })
  });
  await responseJson<Submission>(confirmResponse, "confirm quarantine upload");

  onStage("INSPECTING");
  for (let attempt = 0; attempt < 60; attempt++) {
    const statusResponse = await fetch(`/api/v1/publication-submissions/${creation.submissionId}`, {
      headers: { Accept: "application/json" }
    });
    const submission = await responseJson<Submission>(statusResponse, "read publication submission");
    if (["ADMITTED", "REJECTED", "EXPIRED", "CANCELLED"].includes(submission.state)) return submission;
    await delay(2_000);
  }
  throw new Error("Publication inspection is taking longer than expected");
}

async function sha256(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function responseJson<T>(response: Response, action: string): Promise<T> {
  if (!response.ok) throw new Error(`Unable to ${action} (${response.status})`);
  return response.json() as Promise<T>;
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}
