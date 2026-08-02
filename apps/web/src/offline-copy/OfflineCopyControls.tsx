import { Download, HardDrive, Play, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "../ui";
import type { OfflineCopyManager, OfflineCopyRecord, StorageEstimate } from "./offline-copy-manager";

export type OfflineCopyCapability = Pick<
  OfflineCopyManager,
  "list" | "storageSummary" | "reconcile" | "save" | "evict" | "openPart" | "purgeAll" | "takeEvictionNotices"
>;

export function OfflineCopyControls({
  audiobookId,
  assetVersionId,
  capability,
  installed,
  onPlay
}: {
  audiobookId: string;
  assetVersionId: string;
  capability: OfflineCopyCapability;
  installed: boolean;
  onPlay?: (record: OfflineCopyRecord) => void;
}) {
  const [copy, setCopy] = useState<OfflineCopyRecord>();
  const [storage, setStorage] = useState<(StorageEstimate & { offlineUsage: number; capBytes: number })>();
  const [progress, setProgress] = useState<{ downloaded: number; total: number }>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [evictionNotice, setEvictionNotice] = useState<string>();

  const refresh = async () => {
    const [copies, nextStorage] = await Promise.all([capability.list(), capability.storageSummary()]);
    setCopy(copies.find((candidate) =>
      candidate.audiobookId === audiobookId && candidate.assetVersionId === assetVersionId));
    setStorage(nextStorage);
    const copyId = `${audiobookId}:${assetVersionId}`;
    const eviction = capability.takeEvictionNotices(copyId).at(-1);
    if (eviction) {
      setEvictionNotice(eviction.reason === "EXPIRED"
        ? "Offline Copy was removed because its authorization expired."
        : "Offline Copy was removed because access is no longer authorized.");
    }
  };

  useEffect(() => {
    let active = true;
    const load = async () => {
      if (navigator.onLine) await capability.reconcile();
      if (active) await refresh();
    };
    void load().catch(() => {
      if (active) setError("Offline Copy storage is unavailable on this device.");
    });
    return () => { active = false; };
  }, [audiobookId, assetVersionId, capability]);

  const save = async () => {
    setBusy(true);
    setError(undefined);
    try {
      const saved = await capability.save({
        audiobookId,
        assetVersionId,
        onProgress: (downloaded, total) => setProgress({ downloaded, total })
      });
      setCopy(saved);
      await refresh();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Offline Copy could not be saved.");
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!copy) return;
    setBusy(true);
    setError(undefined);
    await capability.evict(copy.copyId);
    setCopy(undefined);
    setProgress(undefined);
    await refresh();
    setBusy(false);
  };

  return (
    <section className="offline-copy" aria-label="Managed Offline Copy">
      <div className="offline-copy-heading">
        <span><HardDrive size={16} /> Managed Offline Copy</span>
        {copy?.status === "READY" && <strong>Offline Copy ready</strong>}
      </div>
      {storage && (
        <p className="offline-storage">
          {formatBytes(storage.offlineUsage)} of {formatBytes(storage.capBytes)} managed cap used
          {" · "}{formatBytes(Math.max(0, storage.quota - storage.usage))} device storage available
        </p>
      )}
      {!installed && <p>Install Folio to save encrypted audio for offline playback.</p>}
      {progress && busy && (
        <div className="offline-progress" aria-live="polite">
          <progress max={progress.total} value={progress.downloaded} />
          <span>{formatBytes(progress.downloaded)} of {formatBytes(progress.total)} verified and encrypted</span>
        </div>
      )}
      {error && <p role="alert">{error}</p>}
      {evictionNotice && <p role="status">{evictionNotice}</p>}
      <div className="offline-copy-actions">
        {copy?.status === "READY" ? (
          <>
            {onPlay && (
              <Button type="button" variant="outline" onClick={() => onPlay(copy)}>
                <Play size={15} /> Play Offline Copy
              </Button>
            )}
            <Button type="button" variant="ghost" disabled={busy} onClick={() => void remove()}>
              <Trash2 size={15} /> Remove Offline Copy
            </Button>
          </>
        ) : (
          <Button type="button" variant="outline" disabled={!installed || busy} onClick={() => void save()}>
            <Download size={15} /> Save Offline Copy
          </Button>
        )}
      </div>
      <small>
        Managed access, not DRM. Encryption limits casual extraction, but a device owner can still extract audio
        during playback.
      </small>
    </section>
  );
}

function formatBytes(value: number): string {
  if (value >= 1024 ** 3) return `${trim(value / 1024 ** 3)} GB`;
  if (value >= 1024 ** 2) return `${trim(value / 1024 ** 2)} MB`;
  if (value >= 1024) return `${trim(value / 1024)} KB`;
  return `${value} B`;
}

function trim(value: number): string {
  return Number.isInteger(value) ? value.toString() : value.toFixed(1);
}
