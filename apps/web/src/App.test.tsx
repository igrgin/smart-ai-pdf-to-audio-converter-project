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
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
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
              canStartConversion: true,
              source: "DEMONSTRATION_SUBSCRIPTION",
              demonstrationSubscriptionStatus: "CANCEL_AT_PERIOD_END",
              demonstrationOnly: true
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
    expect(screen.getByText(/demonstration subscription/i)).toBeVisible();
    expect(screen.getByText(/not a production payment, tax, payout, or accounting record/i)).toBeVisible();
    expect(screen.getByText(/cancellation is scheduled for the end of the current period/i)).toBeVisible();
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

  it("compares the six issued Narrator Voices and starts at Natural Narration Pace", async () => {
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
      if (url.endsWith("/api/v1/narrator-voices")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            voices: [
              voice("10000000-0000-7000-8000-000000000001", "Rowan", "British English", ["Warm", "Grounded"]),
              voice("10000000-0000-7000-8000-000000000002", "Marlowe", "American English", ["Clear", "Assured"]),
              voice("10000000-0000-7000-8000-000000000003", "Ellis", "Irish English", ["Bright", "Expressive"]),
              voice("10000000-0000-7000-8000-000000000004", "Callum", "British English", ["Calm", "Intimate"]),
              voice("10000000-0000-7000-8000-000000000005", "Ansel", "Australian English", ["Open", "Conversational"]),
              voice(
                "10000000-0000-7000-8000-000000000006",
                "Sloane",
                "American English",
                ["Poised", "Reflective"],
                "TEMPORARILY_UNAVAILABLE"
              )
            ],
            paces: ["MEASURED", "NATURAL", "BRISK"],
            defaultPace: "NATURAL"
          })
        });
      }
      return Promise.resolve({ ok: false, status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: /start a private audiobook/i }));

    const dialog = screen.getByRole("dialog", { name: /create a private audiobook/i });
    expect(await within(dialog).findByRole("button", { name: /select rowan/i })).toHaveAttribute("aria-pressed", "true");
    expect(within(dialog).getByRole("button", { name: /play rowan preview/i })).toBeEnabled();
    expect(within(dialog).getAllByText("British English")).toHaveLength(2);
    expect(within(dialog).getByText("Warm · Grounded")).toBeVisible();
    expect(within(dialog).getAllByText(/29-second standard passage/i)).toHaveLength(6);
    expect(within(dialog).getByRole("button", { name: /select sloane/i })).toBeDisabled();
    expect(within(dialog).getByText(/temporarily unavailable/i)).toBeVisible();
    expect(within(dialog).getByRole("button", { name: "Natural" })).toHaveAttribute("aria-pressed", "true");
    expect(within(dialog).getByText(/playback speed stays independent/i)).toBeVisible();
  });

  it("requires and confirms an explicit new choice when a frozen recipe becomes stale", async () => {
    const conversionId = "01985f42-5f8d-7000-8000-000000000228";
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
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
              conversionId,
              state: "PREPARING",
              version: 1,
              recipeId: "01985f42-5f8d-7000-8000-000000000128",
              voiceId: "10000000-0000-7000-8000-000000000001",
              voiceDisplayName: "Rowan",
              pace: "NATURAL",
              explicitNarrationChoiceRequired: true
            }],
            conversionEntitlement: {
              status: "EXHAUSTED",
              grantedCharacters: 500000,
              availableCharacters: 0,
              reservedCharacters: 500000,
              committedCharacters: 0,
              canStartConversion: false,
              denialReason: "ACTIVE_RESERVATION"
            }
          })
        });
      }
      if (url.endsWith("/api/v1/narrator-voices")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            voices: [voice(
              "10000000-0000-7000-8000-000000000002",
              "Marlowe",
              "American English",
              ["Clear", "Assured"]
            )],
            paces: ["MEASURED", "NATURAL", "BRISK"],
            defaultPace: "NATURAL"
          })
        });
      }
      if (url.endsWith(`/${conversionId}/generation-recipe`) && init?.method === "POST") {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            recipeId: "01985f42-5f8d-7000-8000-000000000328",
            conversionId,
            voiceId: "10000000-0000-7000-8000-000000000002",
            voiceDisplayName: "Marlowe",
            pace: "NATURAL",
            recipeDigest: "c".repeat(64),
            conversionVersion: 2
          })
        });
      }
      return Promise.resolve({ ok: false, status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: /choose a new narrator voice/i }));

    const dialog = screen.getByRole("dialog", { name: /choose a new narrator voice/i });
    expect(await within(dialog).findByRole("button", { name: /select marlowe/i })).toHaveAttribute("aria-pressed", "true");
    expect(within(dialog).queryByLabelText(/choose drm-free epub/i)).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: /confirm new choice/i }));

    expect(await screen.findByText(/marlowe at natural pace is frozen for generation/i)).toBeVisible();
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/audiobook-conversions/${conversionId}/generation-recipe`,
      expect.objectContaining({
        headers: expect.objectContaining({ "If-Match": "\"1\"" }),
        body: JSON.stringify({
          voiceId: "10000000-0000-7000-8000-000000000002",
          pace: "NATURAL"
        })
      })
    );
  });

  it("keeps PDF bytes local until Create audiobook then submits its detected media type", async () => {
    const calls: string[] = [];
    let createBody: unknown;
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
      if (url.endsWith("/api/v1/narrator-voices")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            voices: [voice(
              "10000000-0000-7000-8000-000000000001",
              "Rowan",
              "British English",
              ["Warm", "Grounded"]
            )],
            paces: ["MEASURED", "NATURAL", "BRISK"],
            defaultPace: "NATURAL"
          })
        });
      }
      if (url === "/api/v1/publication-submissions" && init?.method === "POST") {
        createBody = JSON.parse(String(init.body));
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
      if (url.endsWith("/01985f42-5f8d-7000-8000-000000000223/generation-recipe") && init?.method === "POST") {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            recipeId: "01985f42-5f8d-7000-8000-000000000323",
            conversionId: "01985f42-5f8d-7000-8000-000000000223",
            voiceId: "10000000-0000-7000-8000-000000000001",
            voiceDisplayName: "Rowan",
            pace: "NATURAL",
            recipeDigest: "b".repeat(64),
            conversionVersion: 1
          })
        });
      }
      return Promise.resolve({ ok: false, status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: /start a private audiobook/i }));

    expect(await screen.findByRole("button", { name: /select rowan/i })).toHaveAttribute("aria-pressed", "true");
    const pdf = new File(["pdfdata!"], "authorized.pdf", { type: "application/pdf" });
    fireEvent.change(screen.getByLabelText(/choose pdf or drm-free epub/i), { target: { files: [pdf] } });

    expect(screen.getByText(/authorized\.pdf stays on this device/i)).toBeVisible();
    expect(calls.filter((call) => call.includes("publication-submissions"))).toHaveLength(0);

    const dialog = screen.getByRole("dialog", { name: /create a private audiobook/i });
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /i attest/i }));
    fireEvent.click(within(dialog).getByRole("button", { name: /^create audiobook$/i }));

    expect(await screen.findByText(/preparing your private audiobook/i)).toBeVisible();
    expect(calls).toContain("POST /api/v1/publication-submissions");
    expect(createBody).toMatchObject({ mediaType: "application/pdf", byteLength: 8 });
    expect(calls).toContain(
      "PUT /api/v1/publication-submissions/01985f42-5f8d-7000-8000-000000000123/upload"
    );
    expect(calls).toContain(
      "POST /api/v1/publication-submissions/01985f42-5f8d-7000-8000-000000000123/confirm"
    );
    expect(calls).toContain(
      "POST /api/v1/audiobook-conversions/01985f42-5f8d-7000-8000-000000000223/generation-recipe"
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/audiobook-conversions/01985f42-5f8d-7000-8000-000000000223/generation-recipe",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "If-Match": "\"0\"",
          "X-CSRF-TOKEN": "private-csrf"
        }),
        body: JSON.stringify({
          voiceId: "10000000-0000-7000-8000-000000000001",
          pace: "NATURAL"
        })
      })
    );
    expect(screen.getByText(/rowan at natural pace is frozen for generation/i)).toBeVisible();
  });

  it("polls conversion progress and shows provenance-backed review without normal prose", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
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
      if (url.endsWith("/narration-review") && init?.method === "POST") {
        return Promise.resolve({
          ok: true,
          status: 201,
          json: async () => ({
            decisionId: "01985f42-5f8d-7000-8000-000000000225",
            action: "APPROVE",
            conversionVersion: 2
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
                  sourceOrdinal: 1,
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
    expect(screen.getByText(/source position 2 · extraction 99% · classification 98% · treatment 88%/i)).toBeVisible();
    expect(screen.getByText(/table_detected · source: epub xhtml · spine 1 · ops\/chapter.xhtml · anchor facts/i))
      .toBeVisible();
    expect(screen.getByText("Year 2026")).toBeVisible();
    const sectionTitle = screen.getByRole("textbox", { name: /section 1 title/i });
    expect(sectionTitle).toHaveValue("Evidence");
    expect(screen.getByRole("checkbox", { name: /exclude evidence/i })).not.toBeChecked();
    expect(screen.getByRole("combobox", { name: /treatment for table in evidence/i }))
      .toHaveValue("READ_VERBATIM");
    expect(screen.getByRole("textbox", { name: /narration snippet for table in evidence/i }))
      .toHaveValue("Year 2026");
    expect(screen.getByRole("button", { name: /move evidence up/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /move evidence down/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /merge evidence with next section/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /split evidence section/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /skip optional review/i })).toBeEnabled();
    expect(screen.getByRole("button", { name: /approve narration review/i })).toBeEnabled();
    expect(container.textContent).not.toContain("private normal prose");

    fireEvent.change(screen.getByRole("textbox", { name: /section 1 title/i }), {
      target: { value: "Findings" }
    });
    const excludeFindings = await waitFor(() =>
      screen.getByRole("checkbox", { name: /exclude findings/i })
    );
    fireEvent.click(excludeFindings);
    fireEvent.change(screen.getByRole("combobox", { name: /treatment for table in findings/i }), {
      target: { value: "DESCRIBE" }
    });
    fireEvent.change(screen.getByRole("textbox", { name: /narration snippet for table in findings/i }), {
      target: { value: "Describe the 2026 evidence table." }
    });
    fireEvent.click(screen.getByRole("button", { name: /approve narration review/i }));

    expect(await screen.findByRole("heading", { name: /narration review approved/i })).toBeVisible();
    const reviewCall = fetchMock.mock.calls.find(([input, options]) =>
      String(input).endsWith("/narration-review") && options?.method === "POST"
    );
    expect(reviewCall).toBeDefined();
    const reviewOptions = reviewCall?.[1] as RequestInit;
    expect(reviewOptions.headers).toMatchObject({
      "If-Match": "\"1\"",
      "X-CSRF-TOKEN": "private-csrf"
    });
    const reviewBody = JSON.parse(String(reviewOptions.body));
    expect(reviewBody).toEqual({
      action: "APPROVE",
      sections: [{
        clientId: "section-0",
        title: "Findings",
        excluded: true,
        sourceChapterOrdinals: [0],
        reviewItems: [{
          sourceChapterOrdinal: 0,
          ordinal: 0,
          treatment: "DESCRIBE",
          narrationSnippet: "Describe the 2026 evidence table."
        }]
      }]
    });
    expect(JSON.stringify(reviewBody)).not.toMatch(/provenance|confidence|normalProse|sourceOrdinal|type/i);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/audiobook-conversions/01985f42-5f8d-7000-8000-000000000125",
      { headers: { Accept: "application/json" }, signal: expect.any(AbortSignal) }
    );
  });

  it("shows exhausted preparation as requiring intervention instead of pending", async () => {
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
              conversionId: "01985f42-5f8d-7000-8000-000000000126",
              state: "PREPARING",
              reasonCode: "NARRATION_PLAN_REQUIRES_INTERVENTION",
              allowedActions: [],
              version: 4
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
          headers: { get: () => '"4"' },
          json: async () => ({
            conversionId: "01985f42-5f8d-7000-8000-000000000126",
            state: "PREPARING",
            reasonCode: "NARRATION_PLAN_REQUIRES_INTERVENTION",
            allowedActions: [],
            version: 4
          })
        });
      }
      return Promise.resolve({ ok: false, status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByRole("heading", { name: /narration plan needs attention/i })).toBeVisible();
    expect(screen.getByText(/no further automatic attempts are scheduled/i)).toBeVisible();
  });

  it("focuses a recoverable stale-review error and reloads the latest plan without a page refresh", async () => {
    const conversionId = "01985f42-5f8d-7000-8000-000000000127";
    let progressReads = 0;
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
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
              conversionId,
              state: "AWAITING_REVIEW",
              reasonCode: "NARRATION_REVIEW_AVAILABLE",
              allowedActions: ["REVIEW_NARRATION_PLAN", "ACCEPT_RECOMMENDATIONS"],
              version: 1
            }],
            conversionEntitlement: {
              status: "EXHAUSTED",
              grantedCharacters: 500000,
              availableCharacters: 0,
              reservedCharacters: 500000,
              committedCharacters: 0,
              canStartConversion: false
            }
          })
        });
      }
      if (url.endsWith("/narration-review") && init?.method === "POST") {
        return Promise.resolve({
          ok: false,
          status: 409,
          json: async () => ({
            code: "CONVERSION_VERSION_MISMATCH",
            detail: "The Narration Review changed after it was loaded. Reload the latest review and try again.",
            recoverable: true,
            currentVersion: 2
          })
        });
      }
      if (url.endsWith(`/api/v1/audiobook-conversions/${conversionId}`)) {
        progressReads += 1;
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: { get: () => `"${progressReads === 1 ? 1 : 2}"` },
          json: async () => ({
            conversionId,
            state: "AWAITING_REVIEW",
            reasonCode: "NARRATION_REVIEW_AVAILABLE",
            allowedActions: ["REVIEW_NARRATION_PLAN", "ACCEPT_RECOMMENDATIONS"],
            version: progressReads === 1 ? 1 : 2,
            narrationPlan: {
              normalProseEditable: false,
              chapters: [{
                ordinal: 0,
                title: progressReads === 1 ? "Evidence" : "Latest evidence",
                provenance: { source: "EPUB_NAVIGATION", spineIndex: 0, spineItem: "OPS/chapter.xhtml", sourceDeclared: true, confidence: 1 },
                gaps: [],
                reviewItems: [{
                  ordinal: 0,
                  sourceOrdinal: 1,
                  type: "TABLE",
                  provenance: { source: "EPUB_XHTML", spineIndex: 0, spineItem: "OPS/chapter.xhtml", sourceDeclared: true, confidence: 1 },
                  extractionConfidence: 1,
                  classificationConfidence: 1,
                  treatmentConfidence: 1,
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

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: /approve narration review/i }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveFocus();
    expect(alert).toHaveTextContent(/changed after it was loaded/i);
    fireEvent.click(within(alert).getByRole("button", { name: /reload latest review/i }));

    await waitFor(() => expect(screen.getByRole("textbox", { name: /section 1 title/i }))
      .toHaveValue("Latest evidence"));
    expect(progressReads).toBeGreaterThanOrEqual(2);
  });
});

function voice(
  id: string,
  displayName: string,
  englishVariety: string,
  descriptors: string[],
  availability: "AVAILABLE" | "TEMPORARILY_UNAVAILABLE" | "RETIRED" = "AVAILABLE"
) {
  return {
    id,
    displayName,
    englishVariety,
    descriptors,
    descriptorReviewVersion: "voice-review-2026-07",
    availability,
    preview: {
      uri: `/samples/narrator-voices/${displayName.toLowerCase()}-folio-preview-v1.mp3`,
      passageVersion: "folio-preview-v1",
      durationSeconds: 29,
      aiGenerated: true
    }
  };
}
