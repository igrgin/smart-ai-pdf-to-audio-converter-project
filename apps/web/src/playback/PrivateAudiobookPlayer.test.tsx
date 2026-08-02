import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PrivateAudiobookPlayer } from "./PrivateAudiobookPlayer";
import type { OfflineCopyRecord } from "../offline-copy";

const offline = vi.hoisted(() => ({
  manager: {
    list: vi.fn(),
    storageSummary: vi.fn(),
    reconcile: vi.fn(),
    save: vi.fn(),
    evict: vi.fn(),
    openPart: vi.fn(),
    purgeAll: vi.fn(),
    takeEvictionNotices: vi.fn()
  }
}));

vi.mock("../offline-copy", async (importOriginal) => ({
  ...await importOriginal<typeof import("../offline-copy")>(),
  browserSupportsManagedOfflineCopies: () => true,
  createBrowserOfflineCopyManager: () => offline.manager,
  isInstalledPwa: () => true
}));

describe("Private Audiobook managed offline playback", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    offline.manager.list.mockResolvedValue([readyRecord()]);
    offline.manager.storageSummary.mockResolvedValue({
      quota: 1024 ** 3,
      usage: 8,
      offlineUsage: 8,
      capBytes: 512 * 1024 ** 2
    });
    offline.manager.reconcile.mockResolvedValue(undefined);
    offline.manager.takeEvictionNotices.mockReturnValue([]);
    offline.manager.openPart.mockResolvedValue(new Blob([new Uint8Array(4)], { type: "audio/mpeg" }));
  });

  it("switches the persistent player to bounded Blobs and continues to the next offline part", async () => {
    const createObjectURL = vi.fn()
      .mockReturnValueOnce("blob:online-gapless")
      .mockReturnValueOnce("blob:offline-part-1")
      .mockReturnValueOnce("blob:offline-part-2");
    vi.stubGlobal("URL", { createObjectURL, revokeObjectURL: vi.fn() });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => String(input).endsWith("/manifest")
      ? { ok: true, json: async () => playbackManifest() }
      : { ok: true, arrayBuffer: async () => new ArrayBuffer(4) }));
    const play = vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => undefined);

    const { container } = render(
      <PrivateAudiobookPlayer
        audiobooks={[audiobook()]}
        csrf={{ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf" }}
      />
    );

    expect(await screen.findByRole("heading", { name: "First light" })).toBeVisible();
    fireEvent.click(await screen.findByRole("button", { name: /play offline copy/i }));
    const audio = container.querySelector("audio")!;
    await waitFor(() => expect(audio).toHaveAttribute("src", "blob:offline-part-1"));
    expect(offline.manager.openPart).toHaveBeenCalledWith({
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      partId: "part-1"
    });

    fireEvent.ended(audio);
    await waitFor(() => expect(offline.manager.openPart).toHaveBeenLastCalledWith({
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      partId: "part-2"
    }));
    await waitFor(() => expect(audio).toHaveAttribute("src", "blob:offline-part-2"));
    fireEvent.loadedMetadata(audio);
    expect(play).toHaveBeenCalled();
    expect(container.querySelectorAll("audio")).toHaveLength(1);
  });
});

function audiobook() {
  return {
    conversionId: "conversion-1",
    state: "FINALIZED",
    reasonCode: "PRIVATE_AUDIOBOOK_AVAILABLE",
    allowedActions: [],
    version: 1,
    explicitNarrationChoiceRequired: false,
    privateAudiobook: {
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      availability: "AVAILABLE",
      totalDurationMs: 10_000,
      manifestUrl: "/api/v1/audiobooks/book-1/asset-versions/asset-1/manifest"
    }
  };
}

function playbackManifest() {
  return {
    audiobookId: "book-1",
    assetVersionId: "asset-1",
    conversionId: "conversion-1",
    sourceMediaType: "application/pdf",
    narratorVoice: "Rowan",
    manifestDigest: "digest",
    totalDurationMs: 10_000,
    resume: { positionMs: 0, version: 0 },
    chapters: [{
      chapterId: "chapter-1",
      ordinal: 0,
      title: "First light",
      startMs: 0,
      durationMs: 10_000,
      parts: [{
        partId: "part-1",
        ordinal: 0,
        byteLength: 4,
        durationMs: 5_000,
        mimeType: "audio/mpeg",
        entityTag: "sha256:first",
        mediaUrl: "/media/part-1"
      }, {
        partId: "part-2",
        ordinal: 1,
        byteLength: 4,
        durationMs: 5_000,
        mimeType: "audio/mpeg",
        entityTag: "sha256:second",
        mediaUrl: "/media/part-2"
      }]
    }]
  };
}

function readyRecord(): OfflineCopyRecord {
  const now = Date.now();
  return {
    copyId: "book-1:asset-1",
    status: "READY",
    listenerId: "listener-1",
    installationId: "installation-1",
    audiobookId: "book-1",
    assetVersionId: "asset-1",
    authorizationGeneration: 1,
    totalBytes: 8,
    downloadedBytes: 8,
    authorization: {
      algorithm: "ES256",
      keyId: "key",
      publicKey: "key",
      payload: "payload",
      signature: "signature",
      claims: {
        listenerId: "listener-1",
        installationId: "installation-1",
        audiobookId: "book-1",
        assetVersionId: "asset-1",
        authorizationGeneration: 1,
        purpose: "OFFLINE_PLAYBACK",
        issuedAt: new Date(now).toISOString(),
        expiresAt: new Date(now + 60_000).toISOString()
      }
    },
    manifest: {
      ...playbackManifest(),
      totalBytes: 8,
      chapters: [{
        chapterId: "chapter-1",
        ordinal: 0,
        title: "First light",
        startMs: 0,
        durationMs: 10_000,
        partIds: ["part-1", "part-2"]
      }],
      parts: [{
        partId: "part-1",
        ordinal: 0,
        mimeType: "audio/mpeg",
        byteLength: 4,
        durationMs: 5_000,
        entityTag: "sha256:first",
        mediaUrl: "/media/part-1",
        chunks: []
      }, {
        partId: "part-2",
        ordinal: 1,
        mimeType: "audio/mpeg",
        byteLength: 4,
        durationMs: 5_000,
        entityTag: "sha256:second",
        mediaUrl: "/media/part-2",
        chunks: []
      }]
    },
    clock: { serverTime: new Date(now).toISOString(), observedWallClockMs: now, lastWallClockMs: now }
  } as OfflineCopyRecord;
}
