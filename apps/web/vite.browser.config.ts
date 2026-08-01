import react from "@vitejs/plugin-react";
import { playwright } from "@vitest/browser-playwright";
import { createHash, generateKeyPairSync, sign } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import { defineConfig } from "vitest/config";

const media = Buffer.from([1, 2, 3, 4, 5, 6, 7, 8]);
const chunkDigests = [media.subarray(0, 4), media.subarray(4)]
  .map((chunk) => createHash("sha256").update(chunk).digest("hex"));
const signingKeys = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
const publicKey = signingKeys.publicKey.export({ type: "spki", format: "der" }).toString("base64");
let authorizationGeneration = 1;
let interrupted = false;
let rangeRequests: string[] = [];

export default defineConfig({
  plugins: [react(), {
    name: "managed-offline-copy-same-origin-boundary",
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const path = request.url?.split("?", 1)[0];
        if (path === "/__offline-test/reset" && request.method === "POST") {
          authorizationGeneration = 1;
          interrupted = false;
          rangeRequests = [];
          return json(response, 200, { reset: true });
        }
        if (path === "/__offline-test/public-key") return json(response, 200, { publicKey });
        if (path === "/__offline-test/generation" && request.method === "POST") {
          authorizationGeneration += 1;
          return json(response, 200, { authorizationGeneration });
        }
        if (path === "/__offline-test/state") return json(response, 200, { rangeRequests });
        if (path === "/api/v1/browser-acceptance/media" && request.method === "GET") {
          return range(request, response);
        }
        if (path?.match(/^\/api\/v1\/audiobooks\/browser-book\/asset-versions\/browser-asset\/offline-copy-authorizations$/)
            && request.method === "POST") {
          return void authorization(request, response);
        }
        next();
      });
    }
  }],
  test: {
    include: ["src/**/*.browser.test.ts"],
    browser: {
      enabled: true,
      headless: true,
      provider: playwright({ launchOptions: { channel: "chrome" } }),
      instances: [{ browser: "chromium" }]
    }
  }
});

async function authorization(request: IncomingMessage, response: ServerResponse): Promise<void> {
  if (request.headers["x-csrf-token"] !== "browser-csrf" || !request.headers["idempotency-key"]) {
    json(response, 403, { error: "same-origin proof required" });
    return;
  }
  const body = JSON.parse(await requestBody(request)) as { installationId: string };
  const issuedAt = new Date();
  const claims = {
    listenerId: "browser-listener",
    installationId: body.installationId,
    audiobookId: "browser-book",
    assetVersionId: "browser-asset",
    authorizationGeneration,
    purpose: "OFFLINE_PLAYBACK",
    issuedAt: issuedAt.toISOString(),
    expiresAt: new Date(issuedAt.getTime() + 24 * 60 * 60 * 1000).toISOString()
  };
  const payload = Buffer.from(JSON.stringify(claims));
  json(response, 200, {
    serverTime: claims.issuedAt,
    authorization: {
      algorithm: "ES256",
      keyId: "browser-acceptance-key",
      publicKey,
      payload: payload.toString("base64url"),
      signature: sign("sha256", payload, {
        key: signingKeys.privateKey,
        dsaEncoding: "ieee-p1363"
      }).toString("base64url"),
      claims
    },
    manifest: {
      audiobookId: claims.audiobookId,
      assetVersionId: claims.assetVersionId,
      manifestDigest: "browser-manifest-v1",
      sourceMediaType: "application/pdf",
      narratorVoice: "Rowan",
      totalDurationMs: 10_000,
      totalBytes: media.byteLength,
      chapters: [{
        chapterId: "chapter-1",
        ordinal: 0,
        title: "Browser boundary",
        startMs: 0,
        durationMs: 10_000,
        partIds: ["part-1"]
      }],
      parts: [{
        partId: "part-1",
        ordinal: 0,
        mimeType: "audio/mpeg",
        byteLength: media.byteLength,
        durationMs: 10_000,
        entityTag: "sha256:browser-part",
        mediaUrl: "/api/v1/browser-acceptance/media",
        chunks: [
          { ordinal: 0, start: 0, end: 3, byteLength: 4, sha256: chunkDigests[0] },
          { ordinal: 1, start: 4, end: 7, byteLength: 4, sha256: chunkDigests[1] }
        ]
      }]
    }
  });
}

function range(request: IncomingMessage, response: ServerResponse): void {
  const requested = request.headers.range;
  const ifRange = request.headers["if-range"];
  if (!requested?.match(/^bytes=\d+-\d+$/) || ifRange !== '"sha256:browser-part"') {
    json(response, 416, { error: "exact range required" });
    return;
  }
  const [start, end] = requested.slice("bytes=".length).split("-").map(Number);
  rangeRequests.push(`${start}-${end}`);
  if (start === 4 && !interrupted) {
    interrupted = true;
    response.writeHead(503, { "Cache-Control": "no-store", "Retry-After": "0" });
    response.end();
    return;
  }
  const content = media.subarray(start, end + 1);
  response.writeHead(206, {
    "Cache-Control": "no-store",
    "Content-Type": "audio/mpeg",
    "Content-Length": content.byteLength,
    "Content-Range": `bytes ${start}-${end}/${media.byteLength}`,
    ETag: '"sha256:browser-part"'
  });
  response.end(content);
}

function requestBody(request: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let content = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => { content += chunk; });
    request.on("end", () => resolve(content));
    request.on("error", reject);
  });
}

function json(response: ServerResponse, status: number, body: unknown): void {
  response.writeHead(status, { "Cache-Control": "no-store", "Content-Type": "application/json" });
  response.end(JSON.stringify(body));
}
