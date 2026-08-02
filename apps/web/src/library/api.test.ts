import { afterEach, describe, expect, it, vi } from "vitest";
import {
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
});
