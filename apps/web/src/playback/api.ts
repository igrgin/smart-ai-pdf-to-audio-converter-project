import type { CsrfProof } from "../session";

export interface PlaybackManifest {
  audiobookId: string;
  assetVersionId: string;
  conversionId: string;
  sourceMediaType: string;
  narratorVoice: string;
  manifestDigest: string;
  totalDurationMs: number;
  resume: ResumePosition;
  chapters: PlaybackChapter[];
}

export interface ResumePosition {
  positionMs: number;
  version: number;
}

export interface PlaybackChapter {
  chapterId: string;
  ordinal: number;
  title: string;
  startMs: number;
  durationMs: number;
  parts: PlaybackPart[];
}

export interface PlaybackPart {
  partId: string;
  ordinal: number;
  byteLength: number;
  durationMs: number;
  mimeType: string;
  entityTag: string;
  mediaUrl: string;
}

export async function fetchPlaybackManifest(url: string, signal: AbortSignal): Promise<PlaybackManifest> {
  const response = await fetch(url, {
    headers: { Accept: "application/json" },
    signal
  });
  if (!response.ok) throw new Error(`Private Audiobook manifest returned ${response.status}`);
  return response.json() as Promise<PlaybackManifest>;
}

export async function updatePlaybackPosition(
  audiobookId: string,
  assetVersionId: string,
  positionMs: number,
  version: number,
  csrf: CsrfProof
): Promise<ResumePosition> {
  const response = await fetch(
    `/api/v1/audiobooks/${audiobookId}/asset-versions/${assetVersionId}/playback-position`,
    {
      method: "PUT",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "Idempotency-Key": crypto.randomUUID(),
        "If-Match": `"${version}"`,
        [csrf.headerName]: csrf.token
      },
      body: JSON.stringify({ positionMs: Math.round(positionMs) })
    }
  );
  if (!response.ok) throw new Error(`Playback position returned ${response.status}`);
  return response.json() as Promise<ResumePosition>;
}
