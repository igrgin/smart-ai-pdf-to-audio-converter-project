import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { NarrationPlan } from "./api";
import { NarrationReviewEditor } from "./NarrationReviewEditor";

describe("NarrationReviewEditor", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("places the first Review Item before all structural sections and splits only merged source boundaries", () => {
    renderEditor();

    const reviewItem = screen.getByRole("group", { name: /table · read verbatim/i });
    const firstSectionTitle = screen.getByRole("textbox", { name: /section 1 title/i });
    expect(reviewItem.compareDocumentPosition(firstSectionTitle) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
    expect(screen.getByRole("button", { name: /split introduction section/i })).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: /merge introduction with next section/i }));
    expect(screen.getByRole("button", { name: /split introduction section/i })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: /split introduction section/i }));

    expect(screen.getByRole("textbox", { name: /section 2 title/i })).toHaveValue("Introduction (continued)");
    expect(screen.getByRole("combobox", { name: /treatment for table in introduction \(continued\)/i }))
      .toHaveValue("READ_VERBATIM");
  });

  it("reuses the same idempotency key after an ambiguous network failure", async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError("connection closed"))
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          decisionId: "01985f42-5f8d-7000-8000-000000000225",
          action: "APPROVE",
          conversionVersion: 2
        })
      });
    vi.stubGlobal("fetch", fetchMock);
    const onFrozen = vi.fn();
    renderEditor(onFrozen);

    fireEvent.click(screen.getByRole("button", { name: /approve narration review/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/could not be saved/i);
    fireEvent.click(screen.getByRole("button", { name: /approve narration review/i }));

    await waitFor(() => expect(onFrozen).toHaveBeenCalledWith("APPROVE", 2));
    const firstHeaders = fetchMock.mock.calls[0][1]?.headers as Record<string, string>;
    const retryHeaders = fetchMock.mock.calls[1][1]?.headers as Record<string, string>;
    expect(retryHeaders["Idempotency-Key"]).toBe(firstHeaders["Idempotency-Key"]);
  });
});

function renderEditor(onFrozen = vi.fn()) {
  return render(
    <NarrationReviewEditor
      conversionId="01985f42-5f8d-7000-8000-000000000125"
      version={1}
      plan={plan()}
      csrf={{ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "private-csrf" }}
      onFrozen={onFrozen}
      onReload={vi.fn().mockResolvedValue(undefined)}
    />
  );
}

function plan(): NarrationPlan {
  const source = (ordinal: number) => ({
    source: "EPUB_XHTML",
    sourceIndex: ordinal,
    sourceUnit: `OPS/chapter-${ordinal}.xhtml`,
    sourceDeclared: true,
    confidence: 1
  });
  return {
    normalProseEditable: false,
    chapters: [
      {
        ordinal: 0,
        title: "Introduction",
        provenance: source(0),
        gaps: [],
        reviewItems: []
      },
      {
        ordinal: 1,
        title: "Evidence",
        provenance: source(1),
        gaps: [],
        reviewItems: [{
          ordinal: 0,
          sourceOrdinal: 1,
          type: "TABLE",
          provenance: source(1),
          extractionConfidence: 1,
          classificationConfidence: 1,
          treatmentConfidence: 1,
          recommendedTreatment: "READ_VERBATIM",
          narrationSnippet: "Year 2026",
          reasonCode: "TABLE_DETECTED"
        }]
      }
    ]
  };
}
