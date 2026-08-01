import { afterEach, describe, expect, it, vi } from "vitest";
import type { OfflineAuthorizationClaims, SignedOfflineAuthorization } from "./offline-copy-manager";
import {
  BrowserOfflineCopyServer,
  BrowserOfflineCryptography
} from "./browser-adapters";
import { OfflineCopyAuthorizationUnavailableError } from "./offline-copy-manager";

describe("browser Offline Copy boundaries", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("requires an exact authenticated byte-range response", async () => {
    const fetchMock = vi.fn(async () => new Response(new Uint8Array([1, 2, 3, 4]), {
      status: 206,
      headers: { "Content-Range": "bytes 4-7/8" }
    }));
    vi.stubGlobal("fetch", fetchMock);
    const server = new BrowserOfflineCopyServer({
      headerName: "X-CSRF-TOKEN",
      parameterName: "_csrf",
      token: "csrf"
    });

    await expect(server.readRange("/media/part", 4, 7, "sha256:part", new AbortController().signal))
      .resolves.toEqual(new Uint8Array([1, 2, 3, 4]));
    expect(fetchMock).toHaveBeenCalledWith("/media/part", expect.objectContaining({
      cache: "no-store",
      headers: expect.objectContaining({ Range: "bytes=4-7", "If-Range": '"sha256:part"' })
    }));
  });

  it("classifies authoritative authorization denial separately from transient failure", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 404 })));
    const server = new BrowserOfflineCopyServer({
      headerName: "X-CSRF-TOKEN",
      parameterName: "_csrf",
      token: "csrf"
    });

    await expect(server.issue({
      installationId: "installation-1",
      audiobookId: "book-1",
      assetVersionId: "asset-1"
    })).rejects.toBeInstanceOf(OfflineCopyAuthorizationUnavailableError);
  });

  it("verifies the pinned ES256 key and encrypts with a non-extractable installation key", async () => {
    const encodedPublicKey = base64(new Uint8Array([1, 2, 3, 4]));
    const claims: OfflineAuthorizationClaims = {
      listenerId: "listener-1",
      installationId: "installation-1",
      audiobookId: "book-1",
      assetVersionId: "asset-1",
      authorizationGeneration: 1,
      purpose: "OFFLINE_PLAYBACK",
      issuedAt: "2026-08-01T12:00:00Z",
      expiresAt: "2026-08-31T12:00:00Z"
    };
    const payloadBytes = new TextEncoder().encode(JSON.stringify(claims));
    const signature = new Uint8Array([5, 6, 7, 8]);
    const authorization: SignedOfflineAuthorization = {
      algorithm: "ES256",
      keyId: "test-key",
      publicKey: encodedPublicKey,
      payload: base64Url(payloadBytes),
      signature: base64Url(signature),
      claims
    };
    const encryptionKey = { extractable: false } as CryptoKey;
    const plaintext = new TextEncoder().encode("bounded audio bytes");
    const verify = vi.fn(async () => true);
    const encrypt = vi.fn(async () => new Uint8Array([9, 10, 11]).buffer);
    const decrypt = vi.fn(async () => plaintext.slice().buffer);
    vi.stubGlobal("crypto", {
      getRandomValues: <T extends ArrayBufferView | null>(value: T) => value,
      subtle: {
        importKey: vi.fn(async () => ({ extractable: false } as CryptoKey)),
        verify,
        encrypt,
        decrypt
      }
    });
    const boundary = new BrowserOfflineCryptography(
      async () => encryptionKey,
      { "test-key": encodedPublicKey }
    );

    await expect(boundary.verify(authorization)).resolves.toBe(true);
    await expect(boundary.verify({
      ...authorization,
      claims: { ...claims, listenerId: "listener-2" }
    })).resolves.toBe(false);
    const encrypted = await boundary.encrypt(plaintext, "copy-1", "part-1:0");
    expect(encrypted).not.toEqual(plaintext);
    expect(Array.from(await boundary.decrypt(encrypted, "copy-1", "part-1:0")))
      .toEqual(Array.from(plaintext));
    expect(encryptionKey.extractable).toBe(false);
    expect(verify).toHaveBeenCalledWith(
      { name: "ECDSA", hash: "SHA-256" },
      expect.anything(),
      expect.any(ArrayBuffer),
      expect.any(ArrayBuffer)
    );
  });
});

function base64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

function base64Url(bytes: Uint8Array): string {
  return base64(bytes).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}
