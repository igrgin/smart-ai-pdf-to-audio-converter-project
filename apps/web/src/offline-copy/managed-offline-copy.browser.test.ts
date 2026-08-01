import { describe, expect, it } from "vitest";
import {
  BrowserOfflineCopyRepository,
  BrowserOfflineCopyServer,
  BrowserOfflineCryptography
} from "./browser-adapters";
import { OfflineCopyManager } from "./offline-copy-manager";

describe("managed Offline Copy same-origin browser boundary", () => {
  it("crosses real authorization/range HTTP, IndexedDB, OPFS, Web Crypto, and service-worker boundaries", async () => {
    expect(indexedDB).toBeDefined();
    expect(navigator.storage.getDirectory).toBeTypeOf("function");
    await fetch("/__offline-test/reset", { method: "POST" });
    const registration = await navigator.serviceWorker.register("/sw.js");
    await navigator.serviceWorker.ready;

    try {
      const { publicKey } = await fetch("/__offline-test/public-key").then((response) => response.json()) as {
        publicKey: string;
      };
      const repository = new BrowserAcceptanceRepository();
      const cryptography = new BrowserOfflineCryptography(
        (copyId) => repository.encryptionKey(copyId),
        { "browser-acceptance-key": publicKey }
      );
      const server = new BrowserOfflineCopyServer({
        headerName: "X-CSRF-TOKEN",
        parameterName: "_csrf",
        token: "browser-csrf"
      });
      const manager = new OfflineCopyManager(server, repository, cryptography);
      const request = { audiobookId: "browser-book", assetVersionId: "browser-asset" };

      await expect(manager.save(request)).rejects.toThrow();
      expect((await repository.find("browser-book:browser-asset"))?.status).toBe("DOWNLOADING");
      expect(await repository.completedChunks("browser-book:browser-asset"))
        .toEqual(new Set(["part-1:0"]));

      const ready = await manager.save(request);
      expect(ready.status).toBe("READY");
      const state = await fetch("/__offline-test/state").then((response) => response.json()) as {
        rangeRequests: string[];
      };
      expect(state.rangeRequests).toEqual(["0-3", "4-7", "4-7"]);
      expect((await repository.encryptionKey(ready.copyId)).extractable).toBe(false);
      expect(new Uint8Array(await (await manager.openPart({ ...request, partId: "part-1" })).arrayBuffer()))
        .toEqual(new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]));

      await manager.reconcile();
      expect((await repository.find(ready.copyId))?.authorizationGeneration).toBe(1);

      await fetch("/__offline-test/generation", { method: "POST" });
      await manager.reconcile();
      expect(await repository.find(ready.copyId)).toBeUndefined();
      expect(manager.takeEvictionNotices(ready.copyId)).toEqual([{
        copyId: ready.copyId,
        reason: "GENERATION_CHANGED"
      }]);

      const replacement = await manager.save(request);
      expect(replacement.authorizationGeneration).toBe(2);
      await manager.purgeAll();
      expect(await repository.find(replacement.copyId)).toBeUndefined();
      await expect(repository.readEncryptedChunk(replacement.copyId, "part-1:0")).rejects.toThrow();

      const cachedRequests = (await Promise.all(
        (await caches.keys()).map(async (cacheName) => (await caches.open(cacheName)).keys())
      )).flat().map((cached) => new URL(cached.url).pathname);
      expect(cachedRequests.some((path) => path.startsWith("/api/"))).toBe(false);
    } finally {
      await registration.unregister();
      await Promise.all((await caches.keys()).map((cacheName) => caches.delete(cacheName)));
    }
  });
});

class BrowserAcceptanceRepository extends BrowserOfflineCopyRepository {
  override async requestPersistence(): Promise<boolean> {
    await navigator.storage.persist();
    return true;
  }

  override async estimate() {
    const estimate = await super.estimate();
    return { quota: Math.max(estimate.quota, 1024 ** 3), usage: estimate.usage };
  }
}
