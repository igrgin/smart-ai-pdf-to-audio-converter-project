import { describe, expect, it } from "vitest";
import serviceWorker from "../../public/sw.js?raw";

describe("service-worker Cache Storage boundary", () => {
  it("allowlists only the versioned shell and never caches API or media responses", () => {
    expect(serviceWorker).toContain('const CACHE_NAME = "folio-shell-v1"');
    expect(serviceWorker).toContain('const SHELL_URLS = ["/", "/manifest.webmanifest", "/brand-mark.svg"]');
    expect(serviceWorker).toContain("SHELL_URLS.includes(url.pathname) || url.pathname.startsWith(\"/assets/\")");
    expect(serviceWorker).not.toMatch(/cache\.put\([^\n]*(api|media)/i);
    expect(serviceWorker).not.toContain("/api/");
  });
});
