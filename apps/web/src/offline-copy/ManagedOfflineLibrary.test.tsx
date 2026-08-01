import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  ManagedOfflineLibrary,
  type OfflineCopyCapability,
  type OfflineCopyRecord
} from "./index";

describe("offline Library", () => {
  it("validates and decrypts one bounded part into a revocable Blob for playback", async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
    vi.stubGlobal("URL", {
      createObjectURL: vi.fn(() => "blob:bounded-offline-part"),
      revokeObjectURL: vi.fn()
    });
    const record = readyRecord();
    const capability = {
      openPart: vi.fn(async () => new Blob([new Uint8Array(8)], { type: "audio/mpeg" }))
    } as unknown as OfflineCopyCapability;

    const { container, unmount } = render(
      <ManagedOfflineLibrary records={[record]} capability={capability} />
    );

    expect(await screen.findByRole("heading", { name: "First light" })).toBeVisible();
    await waitFor(() => expect(container.querySelector("audio")).toHaveAttribute(
      "src", "blob:bounded-offline-part"
    ));
    expect(capability.openPart).toHaveBeenCalledWith({
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      partId: "part-1"
    });
    expect(container.querySelectorAll("audio")).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: /play offline first light/i }));
    expect(play).toHaveBeenCalled();
    expect(screen.getByText(/not drm/i)).toBeVisible();

    unmount();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:bounded-offline-part");
  });
});

function readyRecord(): OfflineCopyRecord {
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
      keyId: "offline-v1",
      publicKey: "public-key",
      payload: "payload",
      signature: "signature",
      claims: {
        listenerId: "listener-1",
        installationId: "installation-1",
        audiobookId: "book-1",
        assetVersionId: "asset-1",
        authorizationGeneration: 1,
        purpose: "OFFLINE_PLAYBACK",
        issuedAt: "2026-08-01T12:00:00Z",
        expiresAt: "2026-08-31T12:00:00Z"
      }
    },
    manifest: {
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      manifestDigest: "manifest-digest",
      sourceMediaType: "application/pdf",
      narratorVoice: "Rowan",
      totalDurationMs: 10000,
      totalBytes: 8,
      chapters: [{
        chapterId: "chapter-1",
        ordinal: 0,
        title: "First light",
        startMs: 0,
        durationMs: 10000,
        partIds: ["part-1"]
      }],
      parts: [{
        partId: "part-1",
        ordinal: 0,
        mimeType: "audio/mpeg",
        byteLength: 8,
        durationMs: 10000,
        entityTag: "sha256:part-1",
        mediaUrl: "/media/part-1",
        chunks: [{ ordinal: 0, start: 0, end: 7, byteLength: 8, sha256: "hash" }]
      }]
    },
    clock: {
      serverTime: "2026-08-01T12:00:00Z",
      observedWallClockMs: Date.parse("2026-08-01T12:00:00Z"),
      lastWallClockMs: Date.parse("2026-08-01T12:00:00Z")
    }
  };
}
