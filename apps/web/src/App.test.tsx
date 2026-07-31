import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";

describe("public sample", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows the sample and same-origin platform availability", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        apiVersion: "v1",
        build: { version: "0.1.0", revision: "a1b2c3d" },
        availability: { core: "AVAILABLE", database: "AVAILABLE" }
      })
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(screen.getByRole("heading", { name: /stories deserve to be heard/i })).toBeVisible();
    expect(screen.getByRole("button", { name: /play public sample/i })).toBeEnabled();
    expect(screen.getByText(/checking platform/i)).toBeVisible();

    await waitFor(() => expect(screen.getByText(/core online/i)).toBeVisible());
    expect(screen.getByText(/build 0\.1\.0 · a1b2c3d/i)).toBeVisible();
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/platform/status", {
      headers: { Accept: "application/json" },
      signal: expect.any(AbortSignal)
    });
  });

  it("keeps the sample usable when live status is unavailable", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network unavailable")));

    render(<App />);

    await waitFor(() => expect(screen.getByText(/status delayed/i)).toBeVisible());
    expect(screen.getByRole("button", { name: /play public sample/i })).toBeEnabled();
  });
});
