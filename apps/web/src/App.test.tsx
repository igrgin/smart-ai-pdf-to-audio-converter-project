import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";

describe("public sample", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
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

  it("switches between the Clear Signal and Midnight Library themes", () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));

    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /switch to dark mode/i }));

    expect(document.documentElement).toHaveAttribute("data-theme", "dark");
    expect(screen.getByRole("button", { name: /switch to light mode/i })).toBeVisible();
  });

  it("plays, pauses, and resets the public sample when it ends", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    let paused = true;
    vi.spyOn(HTMLMediaElement.prototype, "play").mockImplementation(() => {
      paused = false;
      return Promise.resolve();
    });
    const pause = vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {
      paused = true;
    });
    vi.spyOn(HTMLMediaElement.prototype, "paused", "get").mockImplementation(() => paused);

    const { container } = render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /play public sample/i }));
    await waitFor(() => expect(screen.getByRole("button", { name: /pause public sample/i })).toBeVisible());

    fireEvent.click(screen.getByRole("button", { name: /pause public sample/i }));
    expect(pause).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: /play public sample/i })).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: /play public sample/i }));
    await waitFor(() => expect(screen.getByRole("button", { name: /pause public sample/i })).toBeVisible());
    fireEvent.ended(container.querySelector("audio")!);
    expect(screen.getByRole("button", { name: /play public sample/i })).toBeVisible();
  });

  it("stays ready when the browser rejects audio playback", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    vi.spyOn(HTMLMediaElement.prototype, "play").mockRejectedValue(new DOMException("blocked", "NotAllowedError"));

    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /play public sample/i }));

    await waitFor(() => expect(screen.getByRole("button", { name: /play public sample/i })).toBeVisible());
  });

  it("offers all brokered providers and secure recovery without third-party scripts", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input).endsWith("/api/v1/auth/session")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            authenticated: false,
            csrf: { headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf-test" }
          })
        });
      }
      return Promise.resolve({
        ok: true,
        json: async () => ({
          apiVersion: "v1",
          build: { version: "0.1.0", revision: "test" },
          availability: { core: "AVAILABLE", database: "AVAILABLE" }
        })
      });
    }));

    const { container } = render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: /^sign in$/i }));

    expect(screen.getByRole("dialog", { name: /choose a sign-in method/i })).toBeVisible();
    expect(screen.getByRole("link", { name: /continue with google/i })).toHaveAttribute(
      "href", "/oauth2/authorization/google"
    );
    expect(screen.getByRole("link", { name: /continue with apple/i })).toHaveAttribute(
      "href", "/oauth2/authorization/apple"
    );
    expect(screen.getByRole("link", { name: /continue with facebook/i })).toHaveAttribute(
      "href", "/oauth2/authorization/facebook"
    );
    expect(container.querySelector('form[action="/api/v1/auth/recovery"] input[name="_csrf"]'))
      .toHaveValue("csrf-test");
    expect(container.querySelectorAll("script[src^='http']")).toHaveLength(0);
  });

  it("enters the private responsive Library without exposing a Listener identifier", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/auth/session")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            authenticated: true,
            listener: {
              displayName: "Mara",
              contactEmail: "relay@privaterelay.appleid.com",
              signInMethods: ["apple", "google"]
            },
            csrf: { headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "private-csrf" }
          })
        });
      }
      if (url.endsWith("/api/v1/library")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            displayName: "Mara",
            contactEmail: "relay@privaterelay.appleid.com",
            signInMethods: ["apple", "google"],
            audiobooks: [],
            conversionEntitlement: {
              status: "AVAILABLE",
              grantedCharacters: 500000,
              availableCharacters: 500000,
              reservedCharacters: 0,
              committedCharacters: 0,
              canStartConversion: true
            }
          })
        });
      }
      return Promise.resolve({ ok: false });
    });
    vi.stubGlobal("fetch", fetchMock);

    const { container } = render(<App />);

    expect(await screen.findByRole("heading", { name: /your library/i })).toBeVisible();
    expect(screen.getAllByText("Mara")).toHaveLength(2);
    expect(screen.getByText("relay@privaterelay.appleid.com")).toBeVisible();
    expect(screen.getByText(/your first audiobook will live here/i)).toBeVisible();
    expect(screen.getByText(/500,000 narratable characters available/i)).toBeVisible();
    expect(screen.getByRole("button", { name: /create audiobook/i })).toBeEnabled();
    expect(screen.getByRole("button", { name: /add sign-in method/i })).toBeEnabled();
    expect(container.textContent).not.toContain("01985f42");
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/library", {
      headers: { Accept: "application/json" },
      signal: expect.any(AbortSignal)
    });
  });

  it("clearly denies conversion when the Listener has no free Conversion Entitlement", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/auth/session")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            authenticated: true,
            listener: { displayName: "Mara", signInMethods: ["google"] },
            csrf: { headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "private-csrf" }
          })
        });
      }
      if (url.endsWith("/api/v1/library")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            displayName: "Mara",
            signInMethods: ["google"],
            audiobooks: [],
            conversionEntitlement: {
              status: "NO_GRANT",
              grantedCharacters: 0,
              availableCharacters: 0,
              reservedCharacters: 0,
              committedCharacters: 0,
              canStartConversion: false,
              denialReason: "NO_GRANT"
            }
          })
        });
      }
      return Promise.resolve({ ok: false });
    }));

    render(<App />);

    expect(await screen.findByText(/no free conversion entitlement is available yet/i)).toBeVisible();
    expect(screen.getByRole("button", { name: /create audiobook/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /start a private audiobook/i })).toBeDisabled();
  });

  it("keeps EPUB bytes local until Create audiobook then shows the preparing conversion", async () => {
    const calls: string[] = [];
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push(`${init?.method ?? "GET"} ${url}`);
      if (url.endsWith("/api/v1/auth/session")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            authenticated: true,
            listener: { displayName: "Mara", signInMethods: ["google"] },
            csrf: { headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "private-csrf" }
          })
        });
      }
      if (url.endsWith("/api/v1/library")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            displayName: "Mara",
            signInMethods: ["google"],
            audiobooks: [],
            conversionEntitlement: {
              status: "AVAILABLE",
              grantedCharacters: 500000,
              availableCharacters: 500000,
              reservedCharacters: 0,
              committedCharacters: 0,
              canStartConversion: true
            }
          })
        });
      }
      if (url === "/api/v1/publication-submissions" && init?.method === "POST") {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            submissionId: "01985f42-5f8d-7000-8000-000000000123",
            state: "AWAITING_UPLOAD",
            uploadSession: {
              endpoint: "/api/v1/publication-submissions/01985f42-5f8d-7000-8000-000000000123/upload",
              token: "upload-secret",
              chunkSize: 8388608
            }
          })
        });
      }
      if (url.endsWith("/upload") && init?.method === "PUT") {
        return Promise.resolve({
          ok: true,
          json: async () => ({ nextOffset: 8, complete: true, storageGeneration: "generation-23" })
        });
      }
      if (url.endsWith("/confirm") && init?.method === "POST") {
        return Promise.resolve({ ok: true, json: async () => ({ state: "UPLOADED" }) });
      }
      if (url.endsWith("/01985f42-5f8d-7000-8000-000000000123")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            submissionId: "01985f42-5f8d-7000-8000-000000000123",
            state: "ADMITTED",
            conversionId: "01985f42-5f8d-7000-8000-000000000223"
          })
        });
      }
      return Promise.resolve({ ok: false, status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: /start a private audiobook/i }));

    const epub = new File(["epubdata"], "authorized.epub", { type: "application/epub+zip" });
    fireEvent.change(screen.getByLabelText(/choose drm-free epub/i), { target: { files: [epub] } });

    expect(screen.getByText(/authorized\.epub stays on this device/i)).toBeVisible();
    expect(calls.filter((call) => call.includes("publication-submissions"))).toHaveLength(0);

    const dialog = screen.getByRole("dialog", { name: /create a private audiobook/i });
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /i attest/i }));
    fireEvent.click(within(dialog).getByRole("button", { name: /^create audiobook$/i }));

    expect(await screen.findByText(/preparing your private audiobook/i)).toBeVisible();
    expect(calls).toContain("POST /api/v1/publication-submissions");
    expect(calls).toContain(
      "PUT /api/v1/publication-submissions/01985f42-5f8d-7000-8000-000000000123/upload"
    );
    expect(calls).toContain(
      "POST /api/v1/publication-submissions/01985f42-5f8d-7000-8000-000000000123/confirm"
    );
  });

  it("polls conversion progress and shows provenance-backed review without normal prose", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/auth/session")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            authenticated: true,
            listener: { displayName: "Mara", signInMethods: ["google"] },
            csrf: { headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "private-csrf" }
          })
        });
      }
      if (url.endsWith("/api/v1/library")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            displayName: "Mara",
            signInMethods: ["google"],
            audiobooks: [{
              conversionId: "01985f42-5f8d-7000-8000-000000000125",
              state: "PREPARING",
              reasonCode: "NARRATION_PLAN_PENDING",
              allowedActions: [],
              version: 0
            }],
            conversionEntitlement: {
              status: "EXHAUSTED",
              grantedCharacters: 500000,
              availableCharacters: 0,
              reservedCharacters: 500000,
              committedCharacters: 0,
              canStartConversion: false,
              denialReason: "ACTIVE_CONVERSION_LIMIT"
            }
          })
        });
      }
      if (url.includes("/api/v1/audiobook-conversions/")) {
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: { get: (name: string) => name === "ETag" ? '"1"' : null },
          json: async () => ({
            conversionId: "01985f42-5f8d-7000-8000-000000000125",
            state: "AWAITING_REVIEW",
            reasonCode: "NARRATION_REVIEW_AVAILABLE",
            allowedActions: ["REVIEW_NARRATION_PLAN", "ACCEPT_RECOMMENDATIONS"],
            version: 1,
            narrationPlan: {
              normalProseEditable: false,
              chapters: [{
                ordinal: 0,
                title: "Evidence",
                provenance: {
                  source: "EPUB_NAVIGATION",
                  spineIndex: 0,
                  spineItem: "OPS/chapter.xhtml",
                  anchor: "evidence",
                  sourceDeclared: true,
                  confidence: 1
                },
                gaps: [],
                reviewItems: [{
                  ordinal: 0,
                  type: "TABLE",
                  provenance: {
                    source: "EPUB_XHTML",
                    spineIndex: 0,
                    spineItem: "OPS/chapter.xhtml",
                    anchor: "facts",
                    sourceDeclared: true,
                    confidence: 0.99
                  },
                  extractionConfidence: 0.99,
                  classificationConfidence: 0.98,
                  treatmentConfidence: 0.88,
                  recommendedTreatment: "READ_VERBATIM",
                  narrationSnippet: "Year 2026",
                  reasonCode: "TABLE_DETECTED"
                }]
              }]
            }
          })
        });
      }
      return Promise.resolve({ ok: false, status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);

    const { container } = render(<App />);

    expect(await screen.findByRole("heading", { name: /narration plan ready/i })).toBeVisible();
    expect(screen.getByRole("heading", { name: "Evidence" })).toBeVisible();
    expect(screen.getByText(/source: epub navigation · spine 1/i)).toBeVisible();
    expect(screen.getByText(/table · read verbatim/i)).toBeVisible();
    expect(screen.getByText(/extraction 99% · classification 98% · treatment 88%/i)).toBeVisible();
    expect(screen.getByText("Year 2026")).toBeVisible();
    expect(screen.getByText(/review narration plan/i)).toBeVisible();
    expect(container.textContent).not.toContain("private normal prose");
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/audiobook-conversions/01985f42-5f8d-7000-8000-000000000125",
      { headers: { Accept: "application/json" }, signal: expect.any(AbortSignal) }
    );
  });
});
