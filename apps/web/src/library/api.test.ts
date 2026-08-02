import { afterEach, describe, expect, it, vi } from "vitest";
import {
  deleteListenerAccount,
  deletePrivateAudiobook,
  fetchConversionProgress,
  fetchLibrary
} from "./api";
import { SameOriginResponseError } from "../session";

describe("Audiobook Conversion polling", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses the last entity tag and treats a not-modified response as stable progress", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 304,
      headers: { get: (name: string) => name === "ETag" ? '"7"' : null }
    });
    vi.stubGlobal("fetch", fetchMock);

    const result = await fetchConversionProgress(
      "01985f42-5f8d-7000-8000-000000000125",
      '"7"',
      new AbortController().signal
    );

    expect(result).toEqual({ notModified: true, entityTag: '"7"' });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/audiobook-conversions/01985f42-5f8d-7000-8000-000000000125",
      {
        headers: { Accept: "application/json", "If-None-Match": '"7"' },
        signal: expect.any(AbortSignal)
      }
    );
  });

  it("preserves authoritative private-boundary denial status for offline purge policy", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 404 }));

    const failure = await fetchLibrary(new AbortController().signal).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(SameOriginResponseError);
    expect(failure).toMatchObject({ status: 404 });
  });

  it("requests audiobook deletion with idempotency, version, and CSRF proofs", async () => {
    const receipt = { requestId: "request-1", scope: "AUDIOBOOK", state: "ACCEPTED" };
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: () => receipt });
    vi.stubGlobal("fetch", fetchMock);

    await expect(deletePrivateAudiobook("book-1", 7, csrf())).resolves.toEqual(receipt);
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/audiobooks/book-1", {
      method: "DELETE",
      headers: {
        Accept: "application/json",
        "Idempotency-Key": expect.any(String),
        "If-Match": '"7"',
        "X-CSRF-TOKEN": "csrf-test"
      }
    });
  });

  it("requests account deletion with idempotency and CSRF proofs", async () => {
    const receipt = { requestId: "request-2", scope: "ACCOUNT", state: "ACCEPTED" };
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: () => receipt });
    vi.stubGlobal("fetch", fetchMock);

    await expect(deleteListenerAccount(csrf())).resolves.toEqual(receipt);
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/account", {
      method: "DELETE",
      headers: {
        Accept: "application/json",
        "Idempotency-Key": expect.any(String),
        "X-CSRF-TOKEN": "csrf-test"
      }
    });
  });
});

function csrf() {
  return { headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf-test" };
}
