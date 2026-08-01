import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { OfflineCopyControls, type OfflineCopyCapability, type OfflineCopyRecord } from "./index";

describe("Offline Copy controls", () => {
  it("shows storage, progress, honest extraction limits, and visible eviction", async () => {
    let saved: OfflineCopyRecord | undefined;
    const capability: OfflineCopyCapability = {
      list: vi.fn(async () => saved ? [saved] : []),
      storageSummary: vi.fn(async () => ({
        quota: 2 * 1024 ** 3,
        usage: 256 * 1024 ** 2,
        offlineUsage: 0,
        capBytes: 1024 ** 3
      })),
      reconcile: vi.fn(async () => undefined),
      save: vi.fn(async (request) => {
        request.onProgress?.(4, 8);
        saved = readyRecord();
        request.onProgress?.(8, 8);
        return saved;
      }),
      evict: vi.fn(async () => {
        saved = undefined;
      }),
      openPart: vi.fn(),
      purgeAll: vi.fn(),
      takeEvictionNotices: vi.fn(() => [])
    };

    render(
      <OfflineCopyControls
        audiobookId="book-1"
        assetVersionId="asset-1"
        capability={capability}
        installed
      />
    );

    expect(await screen.findByText(/1 gb managed cap/i)).toBeVisible();
    expect(screen.getByText(/not drm/i)).toBeVisible();
    expect(screen.getByText(/device owner can still extract audio during playback/i)).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: /save offline copy/i }));

    expect(await screen.findByText(/offline copy ready/i)).toBeVisible();
    expect(capability.save).toHaveBeenCalledWith(expect.objectContaining({
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      onProgress: expect.any(Function)
    }));

    fireEvent.click(screen.getByRole("button", { name: /remove offline copy/i }));
    await waitFor(() => expect(capability.evict).toHaveBeenCalledWith("book-1:asset-1"));
    expect(await screen.findByRole("button", { name: /save offline copy/i })).toBeVisible();
  });

  it("requires an installed capable PWA", async () => {
    const capability = {
      list: vi.fn(async () => []),
      storageSummary: vi.fn(async () => ({ quota: 1, usage: 0, offlineUsage: 0, capBytes: 0 })),
      reconcile: vi.fn(async () => undefined),
      takeEvictionNotices: vi.fn(() => [])
    } as unknown as OfflineCopyCapability;

    render(
      <OfflineCopyControls
        audiobookId="book-1"
        assetVersionId="asset-1"
        capability={capability}
        installed={false}
      />
    );

    expect(await screen.findByText(/install folio to save/i)).toBeVisible();
    expect(screen.getByRole("button", { name: /save offline copy/i })).toBeDisabled();
  });

  it("makes an automatic authorization eviction visible", async () => {
    const capability = {
      list: vi.fn(async () => []),
      storageSummary: vi.fn(async () => ({ quota: 1024, usage: 0, offlineUsage: 0, capBytes: 512 })),
      reconcile: vi.fn(async () => undefined),
      takeEvictionNotices: vi.fn(() => [{
        copyId: "book-1:asset-1",
        reason: "ACCESS_REVOKED" as const
      }])
    } as unknown as OfflineCopyCapability;

    render(
      <OfflineCopyControls
        audiobookId="book-1"
        assetVersionId="asset-1"
        capability={capability}
        installed
      />
    );

    expect(await screen.findByRole("status")).toHaveTextContent(/removed because access is no longer authorized/i);
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
      chapters: [],
      parts: []
    },
    clock: {
      serverTime: "2026-08-01T12:00:00Z",
      observedWallClockMs: Date.parse("2026-08-01T12:00:00Z"),
      lastWallClockMs: Date.parse("2026-08-01T12:00:00Z")
    }
  };
}
