const GIB = 1024 ** 3;
const MIB = 1024 ** 2;
const MAX_OFFLINE_BYTES = 4 * GIB;
const MINIMUM_HEADROOM_BYTES = 64 * MIB;
const HEADROOM_RATIO = 0.1;
const CLOCK_ROLLBACK_TOLERANCE_MS = 5 * 60 * 1000;

class OfflineCopyIntegrityError extends Error {}
class OfflineCopyExpiredError extends Error {}

export type OfflineCopyEvictionReason =
  | "ACCESS_REVOKED"
  | "CORRUPT_MEDIA"
  | "EXPIRED"
  | "GENERATION_CHANGED"
  | "INVALID_AUTHORIZATION";

export interface OfflineCopyEvictionNotice {
  copyId: string;
  reason: OfflineCopyEvictionReason;
}

export interface OfflineCopyEvictionNoticeStore {
  add(notice: OfflineCopyEvictionNotice): void;
  take(copyId?: string): OfflineCopyEvictionNotice[];
}

export class InMemoryOfflineCopyEvictionNoticeStore implements OfflineCopyEvictionNoticeStore {
  private readonly notices: OfflineCopyEvictionNotice[] = [];

  add(notice: OfflineCopyEvictionNotice): void {
    this.notices.push(notice);
  }

  take(copyId?: string): OfflineCopyEvictionNotice[] {
    if (copyId === undefined) return this.notices.splice(0);
    const matching = this.notices.filter((notice) => notice.copyId === copyId);
    const retained = this.notices.filter((notice) => notice.copyId !== copyId);
    this.notices.splice(0, this.notices.length, ...retained);
    return matching;
  }
}

export interface StorageEstimate {
  quota: number;
  usage: number;
}

export type CapacityDecision = {
  allowed: boolean;
  capBytes: number;
  headroomBytes: number;
  reason?: "OFFLINE_CAP" | "DEVICE_QUOTA";
};

export function capacityForOfflineCopy(
  requiredBytes: number,
  estimate: StorageEstimate,
  existingOfflineBytes: number,
  alreadyStoredBytes = 0
): CapacityDecision {
  requireNonNegativeInteger(requiredBytes, "requiredBytes");
  requireNonNegativeInteger(estimate.quota, "quota");
  requireNonNegativeInteger(estimate.usage, "usage");
  requireNonNegativeInteger(existingOfflineBytes, "existingOfflineBytes");
  requireNonNegativeInteger(alreadyStoredBytes, "alreadyStoredBytes");
  if (alreadyStoredBytes > requiredBytes) throw new Error("alreadyStoredBytes exceeds requiredBytes");
  const capBytes = Math.min(MAX_OFFLINE_BYTES, Math.floor(estimate.quota * 0.5));
  const headroomBytes = Math.max(MINIMUM_HEADROOM_BYTES, Math.ceil(requiredBytes * HEADROOM_RATIO));
  const plannedOfflineBytes = existingOfflineBytes + requiredBytes + headroomBytes;
  if (plannedOfflineBytes > capBytes) {
    return { allowed: false, capBytes, headroomBytes, reason: "OFFLINE_CAP" };
  }
  if (requiredBytes - alreadyStoredBytes + headroomBytes > Math.max(0, estimate.quota - estimate.usage)) {
    return { allowed: false, capBytes, headroomBytes, reason: "DEVICE_QUOTA" };
  }
  return { allowed: true, capBytes, headroomBytes };
}

export interface OfflineAuthorizationClaims {
  listenerId: string;
  installationId: string;
  audiobookId: string;
  assetVersionId: string;
  authorizationGeneration: number;
  purpose: "OFFLINE_PLAYBACK";
  issuedAt: string;
  expiresAt: string;
}

export interface SignedOfflineAuthorization {
  algorithm: "ES256";
  keyId: string;
  publicKey: string;
  payload: string;
  signature: string;
  claims: OfflineAuthorizationClaims;
}

export interface OfflineChunk {
  ordinal: number;
  start: number;
  end: number;
  byteLength: number;
  sha256: string;
}

export interface OfflinePart {
  partId: string;
  ordinal: number;
  mimeType: string;
  byteLength: number;
  durationMs: number;
  entityTag: string;
  mediaUrl: string;
  chunks: OfflineChunk[];
}

export interface OfflineChapter {
  chapterId: string;
  ordinal: number;
  title: string;
  startMs: number;
  durationMs: number;
  partIds: string[];
}

export interface OfflineManifest {
  audiobookId: string;
  assetVersionId: string;
  manifestDigest: string;
  sourceMediaType: string;
  narratorVoice: string;
  totalDurationMs: number;
  totalBytes: number;
  chapters: OfflineChapter[];
  parts: OfflinePart[];
}

export interface OfflineClockEvidence {
  serverTime: string;
  observedWallClockMs: number;
  lastWallClockMs: number;
}

export interface OfflineCopyRecord {
  copyId: string;
  status: "DOWNLOADING" | "READY";
  listenerId: string;
  installationId: string;
  audiobookId: string;
  assetVersionId: string;
  authorizationGeneration: number;
  totalBytes: number;
  downloadedBytes: number;
  authorization: SignedOfflineAuthorization;
  manifest: OfflineManifest;
  clock: OfflineClockEvidence;
}

export interface OfflineCopyIssue {
  serverTime: string;
  authorization: SignedOfflineAuthorization;
  manifest: OfflineManifest;
}

export class OfflineCopyAuthorizationUnavailableError extends Error {
  constructor(readonly status: number) {
    super(`Offline Copy authorization is unavailable (${status})`);
    this.name = "OfflineCopyAuthorizationUnavailableError";
  }
}

export interface OfflineCopyServer {
  issue(request: {
    installationId: string;
    audiobookId: string;
    assetVersionId: string;
  }): Promise<OfflineCopyIssue>;
  readRange(
    url: string,
    start: number,
    end: number,
    entityTag: string,
    signal: AbortSignal
  ): Promise<Uint8Array>;
}

export interface OfflineCopyRepository {
  requestPersistence(): Promise<boolean>;
  estimate(): Promise<StorageEstimate>;
  offlineUsage(): Promise<number>;
  installation(): Promise<{ installationId: string }>;
  begin(record: OfflineCopyRecord): Promise<unknown>;
  completedChunks(copyId: string): Promise<Set<string>>;
  writeEncryptedChunk(copyId: string, chunkId: string, content: Uint8Array): Promise<void>;
  commit(record: OfflineCopyRecord): Promise<void>;
  find(copyId: string): Promise<OfflineCopyRecord | undefined>;
  list(): Promise<OfflineCopyRecord[]>;
  readEncryptedChunk(copyId: string, chunkId: string): Promise<Uint8Array>;
  updateClock(copyId: string, clock: OfflineClockEvidence): Promise<void>;
  deleteKeyAndMetadata(copyId: string): Promise<void>;
  deleteMedia(copyId: string): Promise<void>;
}

export interface OfflineCryptography {
  verify(authorization: SignedOfflineAuthorization): Promise<boolean>;
  sha256(content: Uint8Array): Promise<string>;
  encrypt(content: Uint8Array, copyId: string, chunkId: string): Promise<Uint8Array>;
  decrypt(content: Uint8Array, copyId: string, chunkId: string): Promise<Uint8Array>;
}

export class OfflineCopyManager {
  constructor(
    private readonly server: OfflineCopyServer,
    private readonly repository: OfflineCopyRepository,
    private readonly cryptography: OfflineCryptography,
    private readonly wallClock: () => number = () => Date.now(),
    private readonly evictionNotices: OfflineCopyEvictionNoticeStore =
      new InMemoryOfflineCopyEvictionNoticeStore()
  ) {}

  async save(request: {
    audiobookId: string;
    assetVersionId: string;
    expectedListenerId?: string;
    signal?: AbortSignal;
    onProgress?: (downloadedBytes: number, totalBytes: number) => void;
  }): Promise<OfflineCopyRecord> {
    if (!await this.repository.requestPersistence()) {
      throw new Error("Persistent storage is required for an Offline Copy");
    }
    const installation = await this.repository.installation();
    const issued = await this.server.issue({
      installationId: installation.installationId,
      audiobookId: request.audiobookId,
      assetVersionId: request.assetVersionId
    });
    await this.validateIssuedAuthorization(issued, request, installation.installationId);
    const copyId = identity(request.audiobookId, request.assetVersionId);
    let previous = await this.repository.find(copyId);
    const resumable = previous
      && previous.listenerId === issued.authorization.claims.listenerId
      && previous.installationId === installation.installationId
      && previous.authorizationGeneration === issued.authorization.claims.authorizationGeneration
      && previous.manifest.manifestDigest === issued.manifest.manifestDigest;
    if (previous && !resumable) {
      await this.evict(copyId);
      previous = undefined;
    }
    const completed = resumable
      ? await this.repository.completedChunks(copyId)
      : new Set<string>();
    let downloadedBytes = completedBytes(issued.manifest, completed);
    const offlineUsage = await this.repository.offlineUsage();
    const capacity = capacityForOfflineCopy(
      issued.manifest.totalBytes,
      await this.repository.estimate(),
      Math.max(0, offlineUsage - (previous?.totalBytes ?? 0)),
      downloadedBytes
    );
    if (!capacity.allowed) {
      throw new Error(capacity.reason === "OFFLINE_CAP"
        ? "Offline Copy exceeds the managed storage cap"
        : "Offline Copy needs more device storage and deterministic headroom");
    }
    const now = this.wallClock();
    const downloading: OfflineCopyRecord = {
      copyId,
      status: "DOWNLOADING",
      listenerId: issued.authorization.claims.listenerId,
      installationId: installation.installationId,
      audiobookId: request.audiobookId,
      assetVersionId: request.assetVersionId,
      authorizationGeneration: issued.authorization.claims.authorizationGeneration,
      totalBytes: issued.manifest.totalBytes,
      downloadedBytes,
      authorization: issued.authorization,
      manifest: issued.manifest,
      clock: { serverTime: issued.serverTime, observedWallClockMs: now, lastWallClockMs: now }
    };
    await this.repository.begin(downloading);

    try {
      request.onProgress?.(downloadedBytes, issued.manifest.totalBytes);
      const controller = request.signal ? undefined : new AbortController();
      const signal = request.signal ?? controller!.signal;
      for (const part of issued.manifest.parts) {
        for (const chunk of part.chunks) {
          const chunkId = `${part.partId}:${chunk.ordinal}`;
          if (completed.has(chunkId)) continue;
          const plaintext = await this.server.readRange(
            part.mediaUrl,
            chunk.start,
            chunk.end,
            part.entityTag,
            signal
          );
          if (plaintext.byteLength !== chunk.byteLength
              || await this.cryptography.sha256(plaintext) !== chunk.sha256) {
            throw new OfflineCopyIntegrityError("Offline Copy chunk verification failed");
          }
          const encrypted = await this.cryptography.encrypt(plaintext, copyId, chunkId);
          await this.repository.writeEncryptedChunk(copyId, chunkId, encrypted);
          downloadedBytes += chunk.byteLength;
          request.onProgress?.(downloadedBytes, issued.manifest.totalBytes);
        }
      }
      const ready = { ...downloading, status: "READY" as const, downloadedBytes };
      await this.repository.commit(ready);
      return ready;
    } catch (error) {
      if (error instanceof OfflineCopyIntegrityError
          || (error instanceof DOMException && error.name === "QuotaExceededError")) {
        await this.evict(copyId);
      }
      throw error;
    }
  }

  async openPart(request: {
    audiobookId: string;
    assetVersionId: string;
    partId: string;
  }): Promise<Blob> {
    const copyId = identity(request.audiobookId, request.assetVersionId);
    const record = await this.repository.find(copyId);
    if (!record || record.status !== "READY") throw new Error("Offline Copy is not ready");
    try {
      await this.validateStoredAuthorization(record, request);
    } catch (error) {
      await this.evictWithNotice(
        copyId,
        error instanceof OfflineCopyExpiredError ? "EXPIRED" : "INVALID_AUTHORIZATION"
      );
      throw error;
    }
    const part = record.manifest.parts.find((candidate) => candidate.partId === request.partId);
    if (!part) throw new Error("Offline Copy part is not authorized");
    const plaintextChunks: Uint8Array[] = [];
    for (const chunk of part.chunks) {
      const chunkId = `${part.partId}:${chunk.ordinal}`;
      const encrypted = await this.repository.readEncryptedChunk(copyId, chunkId);
      const plaintext = await this.cryptography.decrypt(encrypted, copyId, chunkId);
      if (plaintext.byteLength !== chunk.byteLength
          || await this.cryptography.sha256(plaintext) !== chunk.sha256) {
        await this.evictWithNotice(copyId, "CORRUPT_MEDIA");
        throw new Error("Offline Copy decrypted chunk verification failed");
      }
      plaintextChunks.push(plaintext);
    }
    const now = this.wallClock();
    await this.repository.updateClock(copyId, { ...record.clock, lastWallClockMs: now });
    return new Blob(plaintextChunks.map((chunk) => chunk.slice().buffer as ArrayBuffer), { type: part.mimeType });
  }

  async list(): Promise<OfflineCopyRecord[]> {
    const copies = await this.repository.list();
    const available: OfflineCopyRecord[] = [];
    for (const copy of copies) {
      if (this.wallClock() >= Date.parse(copy.authorization.claims.expiresAt)) {
        await this.evictWithNotice(copy.copyId, "EXPIRED");
      } else {
        available.push(copy);
      }
    }
    return available;
  }

  async storageSummary(): Promise<StorageEstimate & { offlineUsage: number; capBytes: number }> {
    const estimate = await this.repository.estimate();
    return {
      ...estimate,
      offlineUsage: await this.repository.offlineUsage(),
      capBytes: Math.min(MAX_OFFLINE_BYTES, Math.floor(estimate.quota * 0.5))
    };
  }

  async reconcile(): Promise<void> {
    const copies = await this.repository.list();
    const installation = await this.repository.installation();
    for (const copy of copies) {
      let issued: OfflineCopyIssue;
      try {
        issued = await this.server.issue({
          installationId: installation.installationId,
          audiobookId: copy.audiobookId,
          assetVersionId: copy.assetVersionId
        });
      } catch (error) {
        if (error instanceof OfflineCopyAuthorizationUnavailableError) {
          await this.evictWithNotice(copy.copyId, "ACCESS_REVOKED");
        }
        continue;
      }
      try {
        await this.validateIssuedAuthorization(issued, {
          audiobookId: copy.audiobookId,
          assetVersionId: copy.assetVersionId,
          expectedListenerId: copy.listenerId
        }, installation.installationId);
        if (issued.authorization.claims.authorizationGeneration !== copy.authorizationGeneration) {
          await this.evictWithNotice(copy.copyId, "GENERATION_CHANGED");
          continue;
        }
      } catch {
        await this.evictWithNotice(copy.copyId, "INVALID_AUTHORIZATION");
        continue;
      }
      const now = this.wallClock();
      await this.repository.commit({
        ...copy,
        authorization: issued.authorization,
        manifest: issued.manifest,
        clock: { serverTime: issued.serverTime, observedWallClockMs: now, lastWallClockMs: now }
      });
    }
  }

  async evict(copyId: string): Promise<void> {
    await this.repository.deleteKeyAndMetadata(copyId);
    try {
      await this.repository.deleteMedia(copyId);
    } catch {
      // Encrypted orphan cleanup is best effort after the authorization key is gone.
    }
  }

  async purgeAll(): Promise<void> {
    const copies = await this.repository.list();
    for (const copy of copies) await this.evictWithNotice(copy.copyId, "ACCESS_REVOKED");
  }

  takeEvictionNotices(copyId?: string): OfflineCopyEvictionNotice[] {
    return this.evictionNotices.take(copyId);
  }

  private async evictWithNotice(copyId: string, reason: OfflineCopyEvictionReason): Promise<void> {
    await this.evict(copyId);
    this.evictionNotices.add({ copyId, reason });
  }

  private async validateIssuedAuthorization(
    issued: OfflineCopyIssue,
    request: { audiobookId: string; assetVersionId: string; expectedListenerId?: string },
    installationId: string
  ): Promise<void> {
    const claims = issued.authorization.claims;
    const times = authorizationTimes(claims);
    const serverTime = Date.parse(issued.serverTime);
    if (!await this.cryptography.verify(issued.authorization)
        || (request.expectedListenerId !== undefined && claims.listenerId !== request.expectedListenerId)
        || claims.installationId !== installationId
        || claims.audiobookId !== request.audiobookId
        || claims.assetVersionId !== request.assetVersionId
        || claims.purpose !== "OFFLINE_PLAYBACK"
        || issued.manifest.audiobookId !== request.audiobookId
        || issued.manifest.assetVersionId !== request.assetVersionId
        || !Number.isFinite(serverTime)
        || serverTime !== times.issuedAt
        || times.expiresAt <= this.wallClock()) {
      throw new Error("Offline Copy authorization is invalid");
    }
  }

  private async validateStoredAuthorization(
    record: OfflineCopyRecord,
    request: { audiobookId: string; assetVersionId: string }
  ): Promise<void> {
    const claims = record.authorization.claims;
    const times = authorizationTimes(claims);
    if (!await this.cryptography.verify(record.authorization)
        || claims.listenerId !== record.listenerId
        || claims.installationId !== record.installationId
        || claims.audiobookId !== request.audiobookId
        || claims.assetVersionId !== request.assetVersionId
        || claims.authorizationGeneration !== record.authorizationGeneration
        || claims.purpose !== "OFFLINE_PLAYBACK") {
      throw new Error("Offline Copy authorization binding is invalid");
    }
    if (record.manifest.audiobookId !== claims.audiobookId
        || record.manifest.assetVersionId !== claims.assetVersionId) {
      throw new Error("Offline Copy manifest binding is invalid");
    }
    const now = this.wallClock();
    if (now >= times.expiresAt) throw new OfflineCopyExpiredError("Offline Copy authorization expired");
    if (now + CLOCK_ROLLBACK_TOLERANCE_MS < record.clock.observedWallClockMs
        || now + CLOCK_ROLLBACK_TOLERANCE_MS < record.clock.lastWallClockMs
        || Date.parse(record.clock.serverTime) > now + CLOCK_ROLLBACK_TOLERANCE_MS) {
      throw new Error("Offline Copy clock evidence is invalid");
    }
  }
}

function authorizationTimes(claims: OfflineAuthorizationClaims): { issuedAt: number; expiresAt: number } {
  const issuedAt = Date.parse(claims.issuedAt);
  const expiresAt = Date.parse(claims.expiresAt);
  if (!Number.isFinite(issuedAt)
      || !Number.isFinite(expiresAt)
      || expiresAt <= issuedAt
      || expiresAt - issuedAt > 30 * 24 * 60 * 60 * 1000) {
    throw new Error("Offline Copy authorization time is invalid");
  }
  return { issuedAt, expiresAt };
}

function identity(audiobookId: string, assetVersionId: string): string {
  return `${audiobookId}:${assetVersionId}`;
}

function completedBytes(manifest: OfflineManifest, completed: Set<string>): number {
  return manifest.parts.reduce((total, part) => total + part.chunks.reduce(
    (partTotal, chunk) => partTotal + (completed.has(`${part.partId}:${chunk.ordinal}`) ? chunk.byteLength : 0),
    0
  ), 0);
}

function requireNonNegativeInteger(value: number, name: string): void {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${name} must be a non-negative integer`);
}
