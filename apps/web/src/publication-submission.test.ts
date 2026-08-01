import { describe, expect, it } from "vitest";
import { publicationMediaType } from "./publication-submission";

describe("publication admission", () => {
  it("accepts only matching PDF and EPUB file declarations", () => {
    expect(publicationMediaType(new File(["pdf"], "authorized.pdf", { type: "application/pdf" })))
      .toBe("application/pdf");
    expect(publicationMediaType(new File(["epub"], "authorized.epub", { type: "application/epub+zip" })))
      .toBe("application/epub+zip");
    expect(publicationMediaType(new File(["spoof"], "authorized.pdf", { type: "application/epub+zip" })))
      .toBeNull();
    expect(publicationMediaType(new File([], "empty.pdf", { type: "application/pdf" })))
      .toBeNull();
  });
});
