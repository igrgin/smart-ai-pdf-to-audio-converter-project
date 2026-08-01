import { describe, expect, it, vi } from "vitest";
import {
  OfflineCopyAuthorizationUnavailableError,
  OfflineCopyManager,
  capacityForOfflineCopy,
  type OfflineCopyRepository,
  type OfflineCopyServer,
  type OfflineCryptography,
  type OfflineAuthorizationClaims,
  type OfflineCopyRecord,
  type OfflinePart
} from "./offline-copy-manager";

const MIB = 1024 * 1024;

describe("managed Offline Copy", () => {
  it("reserves deterministic headroom inside the lesser-of-4-GiB-or-50-percent cap", () => {
    expect(capacityForOfflineCopy(100 * MIB, { quota: 2_000 * MIB, usage: 200 * MIB }, 300 * MIB))
      .toEqual({ allowed: true, capBytes: 1_000 * MIB, headroomBytes: 64 * MIB });

    expect(capacityForOfflineCopy(700 * MIB, { quota: 2_000 * MIB, usage: 200 * MIB }, 300 * MIB))
      .toEqual({ allowed: false, capBytes: 1_000 * MIB, headroomBytes: 70 * MIB, reason: "OFFLINE_CAP" });
  });

  it("charges only missing bytes against free device space when resuming", () => {
    expect(capacityForOfflineCopy(
      600 * MIB,
      { quota: 2_000 * MIB, usage: 1_700 * MIB },
      100 * MIB,
      500 * MIB
    )).toEqual({ allowed: true, capBytes: 1_000 * MIB, headroomBytes: 64 * MIB });
  });

  it("resumes exact ranges, verifies each hash, and encrypts before durable chunk writes", async () => {
    const events: string[] = [];
    const repository = memoryRepository(events, new Set(["part-1:0"]), {
      ...readyRecord(),
      status: "DOWNLOADING",
      downloadedBytes: 4
    });
    const server = serverBoundary(events);
    const cryptography = cryptoBoundary(events);
    const manager = new OfflineCopyManager(server, repository, cryptography, () => Date.parse("2026-08-01T12:00:00Z"));

    const saved = await manager.save({
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      expectedListenerId: "listener-1"
    });

    expect(saved.status).toBe("READY");
    expect(server.readRange).toHaveBeenCalledTimes(1);
    expect(server.readRange).toHaveBeenCalledWith(
      "/media/part-1", 4, 7, "sha256:part-1", expect.any(AbortSignal)
    );
    expect(events).toEqual([
      "persist",
      "issue",
      "begin",
      "range:4-7",
      "hash",
      "encrypt",
      "write:part-1:1",
      "commit"
    ]);
  });

  it("purges the key before best-effort media when a verified chunk does not match", async () => {
    const events: string[] = [];
    const repository = memoryRepository(events, new Set(["part-1:0"]));
    const manager = new OfflineCopyManager(
      serverBoundary(events),
      repository,
      { ...cryptoBoundary(events), sha256: vi.fn(async () => "wrong") },
      () => Date.parse("2026-08-01T12:00:00Z")
    );

    await expect(manager.save({
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      expectedListenerId: "listener-1"
    })).rejects.toThrow("Offline Copy chunk verification failed");

    expect(events.slice(-2)).toEqual(["delete-key", "delete-media"]);
    expect(await repository.list()).toEqual([]);
  });

  it("leaves no committed copy when OPFS reports a quota failure", async () => {
    const events: string[] = [];
    const repository = memoryRepository(events, new Set(["part-1:0"]), {
      ...readyRecord(),
      status: "DOWNLOADING",
      downloadedBytes: 4
    });
    repository.writeEncryptedChunk = vi.fn(async () => {
      throw new DOMException("Device quota exhausted", "QuotaExceededError");
    });
    const manager = new OfflineCopyManager(
      serverBoundary(events),
      repository,
      cryptoBoundary(events),
      () => Date.parse("2026-08-01T12:00:00Z")
    );

    await expect(manager.save({
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      expectedListenerId: "listener-1"
    })).rejects.toMatchObject({ name: "QuotaExceededError" });

    expect(events.slice(-2)).toEqual(["delete-key", "delete-media"]);
    expect(await repository.list()).toEqual([]);
  });

  it("keeps verified chunks and the non-extractable key after a transient range failure", async () => {
    const events: string[] = [];
    const repository = memoryRepository(events);
    const server = serverBoundary(events);
    server.readRange.mockRejectedValueOnce(new TypeError("connection interrupted"));
    const manager = new OfflineCopyManager(
      server,
      repository,
      cryptoBoundary(events),
      () => Date.parse("2026-08-01T12:00:00Z")
    );

    await expect(manager.save({
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      expectedListenerId: "listener-1"
    })).rejects.toThrow("connection interrupted");

    expect(events).not.toContain("delete-key");
    expect(await repository.find("book-1:asset-1")).toMatchObject({
      status: "DOWNLOADING",
      downloadedBytes: 0
    });
  });

  it("purges expired authorization before decrypting", async () => {
    const events: string[] = [];
    const record = readyRecord();
    const repository = memoryRepository(events, undefined, record);
    const crypto = cryptoBoundary(events);
    const manager = new OfflineCopyManager(
      serverBoundary(events),
      repository,
      crypto,
      () => Date.parse("2026-09-01T12:00:00Z")
    );

    await expect(manager.openPart({
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      partId: "part-1"
    })).rejects.toThrow("Offline Copy authorization expired");
    expect(crypto.decrypt).not.toHaveBeenCalled();
    expect(events.slice(-2)).toEqual(["delete-key", "delete-media"]);
    expect(manager.takeEvictionNotices()).toEqual([{
      copyId: "book-1:asset-1",
      reason: "EXPIRED"
    }]);
  });

  it("purges rollback-affected authorization before decrypting", async () => {
    const events: string[] = [];
    const record = readyRecord();
    record.authorization.claims.issuedAt = "2026-09-01T12:00:00Z";
    record.authorization.claims.expiresAt = "2026-09-30T12:00:00Z";
    record.clock.lastWallClockMs = Date.parse("2026-09-02T12:00:00Z");
    const repository = memoryRepository(events, undefined, record);
    const crypto = cryptoBoundary(events);
    const manager = new OfflineCopyManager(
      serverBoundary(events),
      repository,
      crypto,
      () => Date.parse("2026-09-01T12:00:00Z")
    );
    await expect(manager.openPart({
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      partId: "part-1"
    })).rejects.toThrow("Offline Copy clock evidence is invalid");
    expect(crypto.decrypt).not.toHaveBeenCalled();
    expect(events.slice(-2)).toEqual(["delete-key", "delete-media"]);
  });

  it("purges malformed signed time claims before decrypting", async () => {
    const events: string[] = [];
    const record = readyRecord();
    record.authorization.claims.expiresAt = "not-a-time";
    const repository = memoryRepository(events, undefined, record);
    const crypto = cryptoBoundary(events);
    const manager = new OfflineCopyManager(
      serverBoundary(events),
      repository,
      crypto,
      () => Date.parse("2026-08-01T12:00:00Z")
    );

    await expect(manager.openPart({
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      partId: "part-1"
    })).rejects.toThrow("Offline Copy authorization time is invalid");
    expect(crypto.decrypt).not.toHaveBeenCalled();
    expect(events.slice(-2)).toEqual(["delete-key", "delete-media"]);
  });

  it("purges a copy when connected renewal observes an authorization generation change", async () => {
    const events: string[] = [];
    const record = readyRecord();
    const repository = memoryRepository(events, undefined, record);
    const server = serverBoundary(events);
    server.issue.mockResolvedValueOnce({
      serverTime: "2026-08-02T12:00:00Z",
      authorization: {
        algorithm: "ES256",
        keyId: "offline-v1",
        publicKey: "public-key",
        payload: "new-payload",
        signature: "new-signature",
        claims: {
          ...claims(),
          authorizationGeneration: 2,
          issuedAt: "2026-08-02T12:00:00Z"
        }
      },
      manifest: record.manifest
    });
    const manager = new OfflineCopyManager(
      server,
      repository,
      cryptoBoundary(events),
      () => Date.parse("2026-08-02T12:00:00Z")
    );

    await manager.reconcile();

    expect(events.slice(-2)).toEqual(["delete-key", "delete-media"]);
    expect(await repository.list()).toEqual([]);
    expect(manager.takeEvictionNotices()).toEqual([{
      copyId: "book-1:asset-1",
      reason: "GENERATION_CHANGED"
    }]);
  });

  it("purges a copy when connected renewal receives an authoritative denial", async () => {
    const events: string[] = [];
    const record = readyRecord();
    const repository = memoryRepository(events, undefined, record);
    const server = serverBoundary(events);
    server.issue.mockRejectedValueOnce(new OfflineCopyAuthorizationUnavailableError(404));
    const manager = new OfflineCopyManager(server, repository, cryptoBoundary(events));

    await manager.reconcile();

    expect(events.slice(-2)).toEqual(["delete-key", "delete-media"]);
    expect(await repository.list()).toEqual([]);
    expect(manager.takeEvictionNotices()).toEqual([{
      copyId: "book-1:asset-1",
      reason: "ACCESS_REVOKED"
    }]);
  });

  it("preserves a copy when connected renewal fails transiently", async () => {
    const events: string[] = [];
    const record = readyRecord();
    const repository = memoryRepository(events, undefined, record);
    const server = serverBoundary(events);
    server.issue.mockRejectedValueOnce(new TypeError("network unavailable"));
    const manager = new OfflineCopyManager(server, repository, cryptoBoundary(events));

    await manager.reconcile();

    expect(events).not.toContain("delete-key");
    expect(await repository.list()).toEqual([record]);
  });
});

function claims(): OfflineAuthorizationClaims {
  return {
    listenerId: "listener-1",
    installationId: "installation-1",
    audiobookId: "book-1",
    assetVersionId: "asset-1",
    authorizationGeneration: 1,
    purpose: "OFFLINE_PLAYBACK",
    issuedAt: "2026-08-01T12:00:00Z",
    expiresAt: "2026-08-31T12:00:00Z"
  };
}

function part(): OfflinePart {
  return {
    partId: "part-1",
    ordinal: 0,
    mimeType: "audio/mpeg",
    byteLength: 8,
    durationMs: 10000,
    entityTag: "sha256:part-1",
    mediaUrl: "/media/part-1",
    chunks: [
      { ordinal: 0, start: 0, end: 3, byteLength: 4, sha256: "hash-0" },
      { ordinal: 1, start: 4, end: 7, byteLength: 4, sha256: "hash-1" }
    ]
  };
}

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
      claims: claims()
    },
    manifest: manifest(),
    clock: {
      serverTime: "2026-08-01T12:00:00Z",
      observedWallClockMs: Date.parse("2026-08-01T12:00:00Z"),
      lastWallClockMs: Date.parse("2026-08-01T12:00:00Z")
    }
  };
}

function serverBoundary(events: string[]): OfflineCopyServer & {
  issue: ReturnType<typeof vi.fn>;
  readRange: ReturnType<typeof vi.fn>;
} {
  return {
    issue: vi.fn(async () => {
      events.push("issue");
      return {
        serverTime: "2026-08-01T12:00:00Z",
        authorization: {
          algorithm: "ES256" as const,
          keyId: "offline-v1",
          publicKey: "public-key",
          payload: "payload",
          signature: "signature",
          claims: claims()
        },
        manifest: manifest()
      };
    }),
    readRange: vi.fn(async (_url: string, start: number, end: number) => {
      events.push(`range:${start}-${end}`);
      return new Uint8Array(end - start + 1);
    })
  };
}

function manifest() {
  return {
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
    parts: [part()]
  };
}

function cryptoBoundary(events: string[]): OfflineCryptography {
  return {
    verify: vi.fn(async () => true),
    sha256: vi.fn(async () => {
      events.push("hash");
      return "hash-1";
    }),
    encrypt: vi.fn(async (bytes) => {
      events.push("encrypt");
      return bytes;
    }),
    decrypt: vi.fn(async (bytes) => bytes)
  };
}

function memoryRepository(
  events: string[],
  completed = new Set<string>(),
  initial?: OfflineCopyRecord
): OfflineCopyRepository {
  let record = initial;
  return {
    requestPersistence: vi.fn(async () => {
      events.push("persist");
      return true;
    }),
    estimate: vi.fn(async () => ({ quota: 2_000 * MIB, usage: 0 })),
    offlineUsage: vi.fn(async () => 0),
    installation: vi.fn(async () => ({ installationId: "installation-1" })),
    begin: vi.fn(async (next) => {
      events.push("begin");
      record = next;
    }),
    completedChunks: vi.fn(async () => completed),
    writeEncryptedChunk: vi.fn(async (_copyId, chunkId) => {
      events.push(`write:${chunkId}`);
      completed.add(chunkId);
    }),
    commit: vi.fn(async (next) => {
      events.push("commit");
      record = next;
    }),
    find: vi.fn(async () => record),
    list: vi.fn(async () => record ? [record] : []),
    readEncryptedChunk: vi.fn(async () => new Uint8Array(4)),
    updateClock: vi.fn(async (_copyId, clock) => {
      if (record) record.clock = clock;
    }),
    deleteKeyAndMetadata: vi.fn(async () => {
      events.push("delete-key");
      record = undefined;
    }),
    deleteMedia: vi.fn(async () => {
      events.push("delete-media");
    })
  };
}
