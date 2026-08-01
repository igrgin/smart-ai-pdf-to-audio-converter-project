import type { CsrfProof } from "../identity-session";
import type {
  OfflineAuthorizationClaims,
  OfflineClockEvidence,
  OfflineCopyIssue,
  OfflineCopyRecord,
  OfflineCopyRepository,
  OfflineCopyServer,
  OfflineCryptography,
  SignedOfflineAuthorization,
  StorageEstimate
} from "./offline-copy-manager";
import {
  InMemoryOfflineCopyEvictionNoticeStore,
  OfflineCopyAuthorizationUnavailableError,
  OfflineCopyManager
} from "./offline-copy-manager";

const DATABASE_NAME = "folio-managed-offline-copies-v1";
const DATABASE_VERSION = 1;
const DEVICE_KEY = "installation";
const KEY_ID = "offline-v1";
const CONFIGURED_PUBLIC_KEY = import.meta.env.VITE_OFFLINE_AUTHORIZATION_PUBLIC_KEY || "";
const browserEvictionNotices = new InMemoryOfflineCopyEvictionNoticeStore();

interface StoredCopy extends OfflineCopyRecord {
  encryptionKey: CryptoKey;
}

interface StoredChunk {
  id: string;
  copyId: string;
}

export class BrowserOfflineCopyServer implements OfflineCopyServer {
  constructor(private readonly csrf: CsrfProof) {}

  async issue(request: {
    installationId: string;
    audiobookId: string;
    assetVersionId: string;
  }): Promise<OfflineCopyIssue> {
    const response = await fetch(
      `/api/v1/audiobooks/${request.audiobookId}/asset-versions/${request.assetVersionId}/offline-copy-authorizations`,
      {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          "Idempotency-Key": crypto.randomUUID(),
          [this.csrf.headerName]: this.csrf.token
        },
        body: JSON.stringify({ installationId: request.installationId })
      }
    );
    if (!response.ok) {
      if ([401, 403, 404, 410].includes(response.status)) {
        throw new OfflineCopyAuthorizationUnavailableError(response.status);
      }
      throw new Error(`Offline Copy authorization returned ${response.status}`);
    }
    return response.json() as Promise<OfflineCopyIssue>;
  }

  async readRange(
    url: string,
    start: number,
    end: number,
    entityTag: string,
    signal: AbortSignal
  ): Promise<Uint8Array> {
    const response = await fetch(url, {
      headers: {
        Accept: "audio/*",
        Range: `bytes=${start}-${end}`,
        "If-Range": quoteEntityTag(entityTag)
      },
      cache: "no-store",
      signal
    });
    const expectedRange = `bytes ${start}-${end}/*`;
    const actualRange = response.headers.get("Content-Range");
    if (response.status !== 206
        || !actualRange
        || !actualRange.startsWith(expectedRange.slice(0, -1))) {
      throw new Error("Offline Copy range response was not exact");
    }
    return new Uint8Array(await response.arrayBuffer());
  }
}

export class BrowserOfflineCryptography implements OfflineCryptography {
  constructor(
    private readonly keyLookup: (copyId: string) => Promise<CryptoKey>,
    private readonly trustedPublicKeys: Readonly<Record<string, string>> = {
      [KEY_ID]: CONFIGURED_PUBLIC_KEY
    }
  ) {}

  async verify(authorization: SignedOfflineAuthorization): Promise<boolean> {
    const trustedKey = this.trustedPublicKeys[authorization.keyId];
    if (authorization.algorithm !== "ES256" || !trustedKey || authorization.publicKey !== trustedKey) return false;
    const payload = decodeBase64Url(authorization.payload);
    const signature = decodeBase64Url(authorization.signature);
    const claims = parseClaims(payload);
    if (!claims || !sameClaims(claims, authorization.claims)) return false;
    const publicKey = await crypto.subtle.importKey(
      "spki",
      asArrayBuffer(decodeBase64(trustedKey)),
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"]
    );
    return crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      publicKey,
      asArrayBuffer(signature),
      asArrayBuffer(payload)
    );
  }

  async sha256(content: Uint8Array): Promise<string> {
    const digest = await crypto.subtle.digest("SHA-256", asArrayBuffer(content));
    return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, "0")).join("");
  }

  async encrypt(content: Uint8Array, copyId: string, chunkId: string): Promise<Uint8Array> {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv, additionalData: new TextEncoder().encode(`${copyId}\n${chunkId}`) },
      await this.keyLookup(copyId),
      asArrayBuffer(content)
    );
    const encrypted = new Uint8Array(iv.byteLength + ciphertext.byteLength);
    encrypted.set(iv);
    encrypted.set(new Uint8Array(ciphertext), iv.byteLength);
    return encrypted;
  }

  async decrypt(content: Uint8Array, copyId: string, chunkId: string): Promise<Uint8Array> {
    if (content.byteLength < 13) throw new Error("Offline Copy encrypted chunk is invalid");
    const plaintext = await crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: content.slice(0, 12),
        additionalData: new TextEncoder().encode(`${copyId}\n${chunkId}`)
      },
      await this.keyLookup(copyId),
      asArrayBuffer(content.slice(12))
    );
    return new Uint8Array(plaintext);
  }
}

export class BrowserOfflineCopyRepository implements OfflineCopyRepository {
  private readonly database = openDatabase();

  async requestPersistence(): Promise<boolean> {
    if (!("storage" in navigator) || !("persist" in navigator.storage)) return false;
    const alreadyPersisted = "persisted" in navigator.storage && await navigator.storage.persisted();
    return alreadyPersisted || await navigator.storage.persist();
  }

  async estimate(): Promise<StorageEstimate> {
    const estimate = await navigator.storage.estimate();
    return { quota: estimate.quota ?? 0, usage: estimate.usage ?? 0 };
  }

  async offlineUsage(): Promise<number> {
    return (await this.list()).reduce((total, copy) => total + copy.totalBytes, 0);
  }

  async installation(): Promise<{ installationId: string }> {
    const database = await this.database;
    const existing = await requestValue<{ id: string } | undefined>(
      database.transaction("device", "readonly").objectStore("device").get(DEVICE_KEY)
    );
    if (existing) return { installationId: existing.id };
    const installation = { key: DEVICE_KEY, id: crypto.randomUUID() };
    await transactionDone(database, "device", "readwrite", (store) => store.put(installation));
    return { installationId: installation.id };
  }

  async begin(record: OfflineCopyRecord): Promise<void> {
    const database = await this.database;
    const existing = await this.stored(record.copyId);
    const encryptionKey = existing?.encryptionKey ?? await crypto.subtle.generateKey(
      { name: "AES-GCM", length: 256 },
      false,
      ["encrypt", "decrypt"]
    );
    await transactionDone(database, "copies", "readwrite", (store) =>
      store.put({ ...record, encryptionKey } satisfies StoredCopy));
  }

  async completedChunks(copyId: string): Promise<Set<string>> {
    const database = await this.database;
    const chunks = await requestValue<StoredChunk[]>(
      database.transaction("chunks", "readonly").objectStore("chunks").index("copyId").getAll(copyId)
    );
    return new Set(chunks.map((chunk) => chunk.id.slice(copyId.length + 1)));
  }

  async writeEncryptedChunk(copyId: string, chunkId: string, content: Uint8Array): Promise<void> {
    const directory = await this.copyDirectory(copyId, true);
    const file = await directory.getFileHandle(fileName(chunkId), { create: true });
    const writable = await file.createWritable();
    try {
      await writable.write(asArrayBuffer(content));
      await writable.close();
    } catch (error) {
      await writable.abort();
      throw error;
    }
    const database = await this.database;
    await transactionDone(database, "chunks", "readwrite", (store) =>
      store.put({ id: `${copyId}:${chunkId}`, copyId } satisfies StoredChunk));
  }

  async commit(record: OfflineCopyRecord): Promise<void> {
    const database = await this.database;
    const existing = await this.requireStored(record.copyId);
    await transactionDone(database, "copies", "readwrite", (store) =>
      store.put({ ...record, encryptionKey: existing.encryptionKey } satisfies StoredCopy));
  }

  async find(copyId: string): Promise<OfflineCopyRecord | undefined> {
    const stored = await this.stored(copyId);
    return stored ? withoutKey(stored) : undefined;
  }

  async list(): Promise<OfflineCopyRecord[]> {
    const database = await this.database;
    const values = await requestValue<StoredCopy[]>(
      database.transaction("copies", "readonly").objectStore("copies").getAll()
    );
    return values.map(withoutKey);
  }

  async readEncryptedChunk(copyId: string, chunkId: string): Promise<Uint8Array> {
    const directory = await this.copyDirectory(copyId, false);
    const file = await (await directory.getFileHandle(fileName(chunkId))).getFile();
    return new Uint8Array(await file.arrayBuffer());
  }

  async updateClock(copyId: string, clock: OfflineClockEvidence): Promise<void> {
    const database = await this.database;
    const existing = await this.requireStored(copyId);
    await transactionDone(database, "copies", "readwrite", (store) => store.put({ ...existing, clock }));
  }

  async deleteKeyAndMetadata(copyId: string): Promise<void> {
    const database = await this.database;
    const transaction = database.transaction(["copies", "chunks"], "readwrite");
    transaction.objectStore("copies").delete(copyId);
    const chunks = await requestValue<StoredChunk[]>(transaction.objectStore("chunks").index("copyId").getAll(copyId));
    chunks.forEach((chunk) => transaction.objectStore("chunks").delete(chunk.id));
    await transactionCompletion(transaction);
  }

  async deleteMedia(copyId: string): Promise<void> {
    const root = await navigator.storage.getDirectory();
    const folio = await root.getDirectoryHandle("folio-offline-copies", { create: true });
    await folio.removeEntry(directoryName(copyId), { recursive: true });
  }

  async encryptionKey(copyId: string): Promise<CryptoKey> {
    return (await this.requireStored(copyId)).encryptionKey;
  }

  private async stored(copyId: string): Promise<StoredCopy | undefined> {
    const database = await this.database;
    return requestValue<StoredCopy | undefined>(
      database.transaction("copies", "readonly").objectStore("copies").get(copyId)
    );
  }

  private async requireStored(copyId: string): Promise<StoredCopy> {
    const stored = await this.stored(copyId);
    if (!stored) throw new Error("Offline Copy key is unavailable");
    return stored;
  }

  private async copyDirectory(copyId: string, create: boolean): Promise<FileSystemDirectoryHandle> {
    const root = await navigator.storage.getDirectory();
    const folio = await root.getDirectoryHandle("folio-offline-copies", { create });
    return folio.getDirectoryHandle(directoryName(copyId), { create });
  }
}

export function browserSupportsManagedOfflineCopies(): boolean {
  return typeof indexedDB !== "undefined"
    && "storage" in navigator
    && "getDirectory" in navigator.storage
    && "persist" in navigator.storage
    && "estimate" in navigator.storage
    && typeof crypto?.subtle !== "undefined";
}

export function isInstalledPwa(): boolean {
  return window.matchMedia("(display-mode: standalone)").matches
    || Boolean((navigator as Navigator & { standalone?: boolean }).standalone);
}

export function createBrowserOfflineCopyManager(csrf: CsrfProof): OfflineCopyManager {
  const repository = new BrowserOfflineCopyRepository();
  const cryptography = new BrowserOfflineCryptography((copyId) => repository.encryptionKey(copyId));
  return new OfflineCopyManager(
    new BrowserOfflineCopyServer(csrf), repository, cryptography, () => Date.now(), browserEvictionNotices
  );
}

export function createOfflinePlaybackManager(): OfflineCopyManager {
  const repository = new BrowserOfflineCopyRepository();
  const cryptography = new BrowserOfflineCryptography((copyId) => repository.encryptionKey(copyId));
  const unavailableServer: OfflineCopyServer = {
    issue: async () => { throw new Error("Offline renewal requires a connection"); },
    readRange: async () => { throw new Error("Offline download requires a connection"); }
  };
  return new OfflineCopyManager(
    unavailableServer, repository, cryptography, () => Date.now(), browserEvictionNotices
  );
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
    request.onerror = () => reject(request.error);
    request.onupgradeneeded = () => {
      const database = request.result;
      database.createObjectStore("device", { keyPath: "key" });
      database.createObjectStore("copies", { keyPath: "copyId" });
      database.createObjectStore("chunks", { keyPath: "id" }).createIndex("copyId", "copyId");
    };
    request.onsuccess = () => resolve(request.result);
  });
}

function transactionDone(
  database: IDBDatabase,
  storeName: string,
  mode: IDBTransactionMode,
  operation: (store: IDBObjectStore) => void
): Promise<void> {
  const transaction = database.transaction(storeName, mode);
  operation(transaction.objectStore(storeName));
  return transactionCompletion(transaction);
}

function transactionCompletion(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error ?? new Error("Offline Copy transaction aborted"));
  });
}

function requestValue<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function withoutKey(stored: StoredCopy): OfflineCopyRecord {
  const { encryptionKey: _encryptionKey, ...record } = stored;
  return record;
}

function directoryName(copyId: string): string {
  return copyId.replace(/[^A-Za-z0-9._-]/g, "_");
}

function fileName(chunkId: string): string {
  return `${chunkId.replace(/[^A-Za-z0-9._-]/g, "_")}.bin`;
}

function quoteEntityTag(entityTag: string): string {
  return entityTag.startsWith('"') ? entityTag : `"${entityTag}"`;
}

function decodeBase64(value: string): Uint8Array {
  return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
}

function decodeBase64Url(value: string): Uint8Array {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  return decodeBase64(base64);
}

function asArrayBuffer(value: Uint8Array): ArrayBuffer {
  return value.slice().buffer as ArrayBuffer;
}

function parseClaims(payload: Uint8Array): OfflineAuthorizationClaims | undefined {
  try {
    const claims = JSON.parse(new TextDecoder().decode(payload)) as Partial<OfflineAuthorizationClaims>;
    if (typeof claims.listenerId !== "string"
        || typeof claims.installationId !== "string"
        || typeof claims.audiobookId !== "string"
        || typeof claims.assetVersionId !== "string"
        || !Number.isSafeInteger(claims.authorizationGeneration)
        || claims.authorizationGeneration! <= 0
        || claims.purpose !== "OFFLINE_PLAYBACK"
        || typeof claims.issuedAt !== "string"
        || typeof claims.expiresAt !== "string") {
      return undefined;
    }
    return claims as OfflineAuthorizationClaims;
  } catch {
    return undefined;
  }
}

function sameClaims(left: OfflineAuthorizationClaims, right: OfflineAuthorizationClaims): boolean {
  return left.listenerId === right.listenerId
    && left.installationId === right.installationId
    && left.audiobookId === right.audiobookId
    && left.assetVersionId === right.assetVersionId
    && left.authorizationGeneration === right.authorizationGeneration
    && left.purpose === right.purpose
    && left.issuedAt === right.issuedAt
    && left.expiresAt === right.expiresAt;
}
