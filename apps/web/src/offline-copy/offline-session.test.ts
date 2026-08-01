import { afterEach, describe, expect, it, vi } from "vitest";
import { SameOriginResponseError, type IdentitySession, type Library } from "../identity-session";
import type { OfflineCopyCapability } from "./OfflineCopyControls";
import { monitorConnectedPrivateAccess, resolvePrivateAccess } from "./offline-session";
import type { OfflineCopyRecord } from "./offline-copy-manager";

describe("private access Offline Copy lifecycle", () => {
  afterEach(() => vi.useRealTimers());

  it("plays a valid Offline Copy when the browser reports online but the origin is unreachable", async () => {
    const playback = capability([readyRecord()]);
    const result = await resolvePrivateAccess(new AbortController().signal, {
      offlineCapable: true,
      fetchSession: vi.fn().mockRejectedValue(new TypeError("origin unreachable")),
      fetchLibrary: vi.fn(),
      playbackManager: () => playback,
      connectedManager: vi.fn()
    });

    expect(result.offline?.records).toHaveLength(1);
    expect(playback.list).toHaveBeenCalledOnce();
  });

  it("reconciles every stored copy before loading a Library that no longer contains it", async () => {
    const connected = capability([]);
    const reconcile = connected.reconcile as ReturnType<typeof vi.fn>;
    const fetchLibrary = vi.fn(async () => library());
    const result = await resolvePrivateAccess(new AbortController().signal, {
      offlineCapable: true,
      fetchSession: vi.fn(async () => authenticatedSession()),
      fetchLibrary,
      playbackManager: () => capability([]),
      connectedManager: () => connected
    });

    expect(reconcile).toHaveBeenCalledOnce();
    expect(reconcile.mock.invocationCallOrder[0]).toBeLessThan(fetchLibrary.mock.invocationCallOrder[0]);
    expect(result.library).toEqual(library());
  });

  it("purges keys before returning from an authoritative connected denial", async () => {
    const events: string[] = [];
    const playback = capability([readyRecord()]);
    playback.purgeAll = vi.fn(async () => { events.push("keys-purged"); });
    const result = await resolvePrivateAccess(new AbortController().signal, {
      offlineCapable: true,
      fetchSession: vi.fn(async () => authenticatedSession()),
      fetchLibrary: vi.fn(async () => { throw new SameOriginResponseError(410, "Library"); }),
      playbackManager: () => playback,
      connectedManager: () => capability([])
    });

    expect(events).toEqual(["keys-purged"]);
    expect(result.offline).toBeUndefined();
    expect(result.evictedCount).toBeGreaterThan(0);
  });

  it("purges local access after a successful unauthenticated session response", async () => {
    const playback = capability([readyRecord()]);
    await resolvePrivateAccess(new AbortController().signal, {
      offlineCapable: true,
      fetchSession: vi.fn(async () => ({
        authenticated: false,
        csrf: { headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf" }
      })),
      fetchLibrary: vi.fn(),
      playbackManager: () => playback,
      connectedManager: vi.fn()
    });

    expect(playback.purgeAll).toHaveBeenCalledOnce();
  });

  it("reconciles periodically and on focus while a connected PWA remains open", async () => {
    vi.useFakeTimers();
    const refresh = vi.fn(async () => undefined);
    const stop = monitorConnectedPrivateAccess(refresh, 1_000);

    await vi.advanceTimersByTimeAsync(1_000);
    window.dispatchEvent(new Event("focus"));
    await Promise.resolve();

    expect(refresh).toHaveBeenCalledTimes(2);
    stop();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(refresh).toHaveBeenCalledTimes(2);
  });
});

function capability(records: OfflineCopyRecord[]): OfflineCopyCapability & Record<string, ReturnType<typeof vi.fn>> {
  return {
    list: vi.fn(async () => records),
    storageSummary: vi.fn(),
    reconcile: vi.fn(),
    save: vi.fn(),
    evict: vi.fn(),
    openPart: vi.fn(),
    purgeAll: vi.fn(),
    takeEvictionNotices: vi.fn(() => [])
  } as OfflineCopyCapability & Record<string, ReturnType<typeof vi.fn>>;
}

function authenticatedSession(): IdentitySession {
  return {
    authenticated: true,
    listener: { displayName: "Mara", signInMethods: ["google"] },
    csrf: { headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf" }
  };
}

function library(): Library {
  return {
    displayName: "Mara",
    signInMethods: ["google"],
    audiobooks: [],
    conversionEntitlement: {
      status: "AVAILABLE",
      grantedCharacters: 1,
      availableCharacters: 1,
      reservedCharacters: 0,
      committedCharacters: 0,
      canStartConversion: true
    }
  };
}

function readyRecord(): OfflineCopyRecord {
  const now = Date.now();
  return {
    copyId: "book:asset",
    status: "READY",
    listenerId: "listener",
    installationId: "installation",
    audiobookId: "book",
    assetVersionId: "asset",
    authorizationGeneration: 1,
    totalBytes: 1,
    downloadedBytes: 1,
    authorization: {
      algorithm: "ES256",
      keyId: "key",
      publicKey: "key",
      payload: "payload",
      signature: "signature",
      claims: {
        listenerId: "listener",
        installationId: "installation",
        audiobookId: "book",
        assetVersionId: "asset",
        authorizationGeneration: 1,
        purpose: "OFFLINE_PLAYBACK",
        issuedAt: new Date(now).toISOString(),
        expiresAt: new Date(now + 60_000).toISOString()
      }
    },
    manifest: {
      audiobookId: "book",
      assetVersionId: "asset",
      manifestDigest: "digest",
      sourceMediaType: "application/pdf",
      narratorVoice: "Rowan",
      totalDurationMs: 1,
      totalBytes: 1,
      chapters: [],
      parts: []
    },
    clock: { serverTime: new Date(now).toISOString(), observedWallClockMs: now, lastWallClockMs: now }
  };
}
