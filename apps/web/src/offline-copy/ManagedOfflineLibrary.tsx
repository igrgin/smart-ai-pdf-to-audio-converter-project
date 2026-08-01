import { BookOpen, Pause, Play } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Button } from "../components/ui/button";
import type { OfflineCopyCapability } from "./OfflineCopyControls";
import type { OfflineChapter, OfflineCopyRecord, OfflinePart } from "./offline-copy-manager";

interface PositionedOfflinePart {
  chapter: OfflineChapter;
  part: OfflinePart;
  startMs: number;
}

export function ManagedOfflineLibrary({
  records,
  capability
}: {
  records: OfflineCopyRecord[];
  capability: OfflineCopyCapability;
}) {
  const ready = records.filter((record) => record.status === "READY");
  const [selectedCopyId, setSelectedCopyId] = useState(ready[0]?.copyId ?? "");
  const selected = ready.find((record) => record.copyId === selectedCopyId) ?? ready[0];
  const parts = useMemo(() => selected ? positionedParts(selected) : [], [selected]);
  const [activeIndex, setActiveIndex] = useState(0);
  const [sourceUrl, setSourceUrl] = useState<string>();
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);
  const [error, setError] = useState<string>();
  const continueRef = useRef(false);
  const audioRef = useRef<HTMLAudioElement>(null);
  const active = parts[activeIndex];

  useEffect(() => {
    setActiveIndex(0);
    setPlaying(false);
  }, [selected?.copyId]);

  useEffect(() => {
    if (!selected || !active) return;
    let disposed = false;
    let objectUrl: string | undefined;
    setSourceUrl(undefined);
    setError(undefined);
    void capability.openPart({
      audiobookId: selected.audiobookId,
      assetVersionId: selected.assetVersionId,
      partId: active.part.partId
    }).then((blob) => {
      if (disposed) return;
      objectUrl = URL.createObjectURL(blob);
      setSourceUrl(objectUrl);
    }).catch((failure) => {
      if (!disposed) setError(failure instanceof Error ? failure.message : "Offline playback is unavailable.");
    });
    return () => {
      disposed = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [selected?.copyId, active?.part.partId, capability]);

  if (!selected || !active) return null;

  return (
    <section className="library-studio offline-library" aria-labelledby="offline-library-title">
      <div className="library-heading">
        <div>
          <div className="eyebrow"><BookOpen size={15} /> Encrypted on this installation</div>
          <h1 id="offline-library-title">Offline Library</h1>
          <p>Authorization expires {formatDate(selected.authorization.claims.expiresAt)}.</p>
        </div>
      </div>

      {ready.length > 1 && (
        <nav className="audiobook-switcher" aria-label="Offline Copies">
          {ready.map((copy, index) => (
            <Button
              key={copy.copyId}
              type="button"
              variant="outline"
              aria-pressed={copy.copyId === selected.copyId}
              onClick={() => setSelectedCopyId(copy.copyId)}
            >
              Offline audiobook {index + 1}
            </Button>
          ))}
        </nav>
      )}

      <article className="private-player">
        <div className="private-player-heading">
          <span className="empty-mark" aria-hidden="true"><BookOpen size={28} /></span>
          <div>
            <span className="card-kicker">Chapter {active.chapter.ordinal + 1} of {selected.manifest.chapters.length}</span>
            <h2>{active.chapter.title}</h2>
            <p>{publicationLabel(selected.manifest.sourceMediaType)} · Narrated by {selected.manifest.narratorVoice}</p>
          </div>
        </div>

        {error && <p role="alert">{error}</p>}
        <div className="private-player-controls">
          <Button
            type="button"
            size="icon"
            disabled={!sourceUrl}
            aria-label={`${playing ? "Pause" : "Play"} offline ${active.chapter.title}`}
            onClick={() => {
              const audio = audioRef.current;
              if (!audio) return;
              if (audio.paused) void audio.play(); else audio.pause();
            }}
          >
            {playing ? <Pause size={18} fill="currentColor" /> : <Play size={18} fill="currentColor" />}
          </Button>
          <label className="playback-speed">
            <span>Playback speed</span>
            <select
              aria-label="Offline playback speed"
              value={speed}
              onChange={(event) => {
                const next = Number(event.target.value);
                setSpeed(next);
                if (audioRef.current) audioRef.current.playbackRate = next;
              }}
            >
              {[0.75, 1, 1.25, 1.5, 2].map((value) => <option key={value} value={value}>{value}×</option>)}
            </select>
          </label>
        </div>

        <nav className="chapter-list" aria-label="Offline audiobook chapters">
          {selected.manifest.chapters.map((chapter) => (
            <button
              type="button"
              key={chapter.chapterId}
              aria-current={chapter.chapterId === active.chapter.chapterId ? "true" : undefined}
              onClick={() => setActiveIndex(parts.findIndex((candidate) =>
                candidate.chapter.chapterId === chapter.chapterId))}
            >
              <span>{chapter.ordinal + 1}</span>
              <strong>{chapter.title}</strong>
              <small>{formatTime(chapter.durationMs)}</small>
            </button>
          ))}
        </nav>

        <audio
          ref={audioRef}
          src={sourceUrl}
          preload="metadata"
          onLoadedMetadata={(event) => {
            event.currentTarget.playbackRate = speed;
            if (continueRef.current) {
              continueRef.current = false;
              void event.currentTarget.play();
            }
          }}
          onPlay={() => setPlaying(true)}
          onPause={() => setPlaying(false)}
          onEnded={() => {
            if (activeIndex + 1 < parts.length) {
              continueRef.current = true;
              setActiveIndex(activeIndex + 1);
            } else {
              setPlaying(false);
            }
          }}
        />
        <small className="offline-boundary">
          Managed access, not DRM. A device owner can extract audio while a bounded part is decrypted for playback.
        </small>
      </article>
    </section>
  );
}

function positionedParts(record: OfflineCopyRecord): PositionedOfflinePart[] {
  return record.manifest.chapters.flatMap((chapter) => {
    let startMs = chapter.startMs;
    return chapter.partIds.map((partId) => {
      const part = record.manifest.parts.find((candidate) => candidate.partId === partId);
      if (!part) throw new Error("Offline Copy manifest part is missing");
      const positioned = { chapter, part, startMs };
      startMs += part.durationMs;
      return positioned;
    });
  });
}

function publicationLabel(mediaType: string): string {
  return mediaType === "application/pdf" ? "PDF publication" : "EPUB publication";
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

function formatTime(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}
