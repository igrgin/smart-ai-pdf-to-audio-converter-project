import {
  SameOriginResponseError,
  type CsrfProof,
  type IdentitySession,
  type Library
} from "../identity-session";
import type { OfflineCopyCapability } from "./OfflineCopyControls";
import type { OfflineCopyRecord } from "./offline-copy-manager";

export interface PrivateAccessDependencies {
  offlineCapable: boolean;
  fetchSession(signal: AbortSignal): Promise<IdentitySession>;
  fetchLibrary(signal: AbortSignal): Promise<Library>;
  playbackManager(): OfflineCopyCapability;
  connectedManager(csrf: CsrfProof): OfflineCopyCapability;
}

export interface PrivateAccessResolution {
  session?: IdentitySession;
  library?: Library;
  offline?: { records: OfflineCopyRecord[]; capability: OfflineCopyCapability };
  evictedCount: number;
}

export async function resolvePrivateAccess(
  signal: AbortSignal,
  dependencies: PrivateAccessDependencies
): Promise<PrivateAccessResolution> {
  let session: IdentitySession;
  try {
    session = await dependencies.fetchSession(signal);
  } catch (error) {
    if (authoritativeDenial(error)) return purge(dependencies);
    return offlineFallback(dependencies);
  }

  if (!session.authenticated) {
    const result = await purge(dependencies);
    return { ...result, session };
  }

  let evictedCount = 0;
  if (dependencies.offlineCapable) {
    const connected = dependencies.connectedManager(session.csrf);
    try {
      await connected.reconcile();
      evictedCount += connected.takeEvictionNotices().length;
    } catch {
      // Private Library access remains useful when renewal storage or the network is temporarily unavailable.
    }
  }

  try {
    return {
      session,
      library: await dependencies.fetchLibrary(signal),
      evictedCount
    };
  } catch (error) {
    if (authoritativeDenial(error)) {
      const result = await purge(dependencies);
      return { ...result, session, evictedCount: evictedCount + result.evictedCount };
    }
    const result = await offlineFallback(dependencies);
    return { ...result, session, evictedCount: evictedCount + result.evictedCount };
  }
}

export function authoritativeDenial(error: unknown): boolean {
  return error instanceof SameOriginResponseError && [401, 403, 404, 410].includes(error.status);
}

export function monitorConnectedPrivateAccess(
  refresh: () => Promise<void>,
  intervalMs = 60_000
): () => void {
  const refreshWhenVisibleAndConnected = () => {
    if (navigator.onLine && document.visibilityState === "visible") void refresh();
  };
  const timer = window.setInterval(refreshWhenVisibleAndConnected, intervalMs);
  window.addEventListener("online", refreshWhenVisibleAndConnected);
  window.addEventListener("focus", refreshWhenVisibleAndConnected);
  document.addEventListener("visibilitychange", refreshWhenVisibleAndConnected);
  return () => {
    window.clearInterval(timer);
    window.removeEventListener("online", refreshWhenVisibleAndConnected);
    window.removeEventListener("focus", refreshWhenVisibleAndConnected);
    document.removeEventListener("visibilitychange", refreshWhenVisibleAndConnected);
  };
}

async function offlineFallback(dependencies: PrivateAccessDependencies): Promise<PrivateAccessResolution> {
  if (!dependencies.offlineCapable) return { evictedCount: 0 };
  const capability = dependencies.playbackManager();
  const records = await capability.list();
  const evictedCount = capability.takeEvictionNotices().length;
  return {
    offline: records.some((record) => record.status === "READY") ? { records, capability } : undefined,
    evictedCount
  };
}

async function purge(dependencies: PrivateAccessDependencies): Promise<PrivateAccessResolution> {
  if (!dependencies.offlineCapable) return { evictedCount: 0 };
  const capability = dependencies.playbackManager();
  const records = await capability.list();
  await capability.purgeAll();
  return { evictedCount: Math.max(records.length, capability.takeEvictionNotices().length) };
}
