import { BookOpen, Pause, Play, SkipBack, SkipForward } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Button } from "../ui";
import { prepareGaplessPlayback } from "./gapless-media-source";
import type { CsrfProof } from "../session";
import {
  OfflineCopyControls,
  browserSupportsManagedOfflineCopies,
  createBrowserOfflineCopyManager,
  isInstalledPwa,
  type OfflineCopyRecord
} from "../offline-copy";
import {
  fetchPlaybackManifest,
  updatePlaybackPosition,
  type PlaybackChapter,
  type PlaybackManifest,
  type PlaybackPart
} from "./api";

interface PositionedPart {
  chapter: PlaybackChapter;
  part: PlaybackPart;
  startMs: number;
}

export interface PlayableAudiobook {
  state: string;
  privateAudiobook?: {
    audiobookId: string;
    availability: string;
    manifestUrl: string;
  };
}

export function PrivateAudiobookPlayer({
  audiobooks,
  csrf
}: {
  audiobooks: PlayableAudiobook[];
  csrf: CsrfProof;
}) {
  const playable = audiobooks.filter((candidate) =>
    candidate.state === "FINALIZED"
    && candidate.privateAudiobook?.availability === "AVAILABLE"
  );
  const [selectedId, setSelectedId] = useState(() => playable[0]?.privateAudiobook?.audiobookId ?? "");
  const selected = playable.find((candidate) => candidate.privateAudiobook?.audiobookId === selectedId)
    ?? playable[0];
  const [manifest, setManifest] = useState<PlaybackManifest | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [activePartIndex, setActivePartIndex] = useState(0);
  const [positionMs, setPositionMs] = useState(0);
  const [resumeVersion, setResumeVersion] = useState(0);
  const [isPlaying, setIsPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);
  const [gaplessSourceUrl, setGaplessSourceUrl] = useState<string | null>(null);
  const [offlineSourceUrl, setOfflineSourceUrl] = useState<string | null>(null);
  const [offlineCopy, setOfflineCopy] = useState<OfflineCopyRecord | null>(null);
  const [offlinePlayback, setOfflinePlayback] = useState(false);
  const audioRef = useRef<HTMLAudioElement>(null);
  const resumeVersionRef = useRef(0);
  const continueOfflineRef = useRef(false);
  const offlineCapability = useMemo(
    () => browserSupportsManagedOfflineCopies() ? createBrowserOfflineCopyManager(csrf) : null,
    [csrf.headerName, csrf.token]
  );

  useEffect(() => {
    if (selected?.privateAudiobook && selected.privateAudiobook.audiobookId !== selectedId) {
      setSelectedId(selected.privateAudiobook.audiobookId);
    }
  }, [selected?.privateAudiobook?.audiobookId, selectedId]);

  useEffect(() => {
    const summary = selected?.privateAudiobook;
    if (!summary) return;
    const controller = new AbortController();
    setManifest(null);
    setUnavailable(false);
    setIsPlaying(false);
    audioRef.current?.pause();
    fetchPlaybackManifest(summary.manifestUrl, controller.signal)
      .then((loaded) => {
        const parts = positionedParts(loaded);
        const index = partIndexAt(parts, loaded.resume.positionMs);
        setManifest(loaded);
        setActivePartIndex(index);
        setPositionMs(loaded.resume.positionMs);
        setResumeVersion(loaded.resume.version);
        resumeVersionRef.current = loaded.resume.version;
      })
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) setUnavailable(true);
      });
    return () => controller.abort();
  }, [selected?.privateAudiobook?.manifestUrl]);

  const parts = useMemo(() => manifest ? positionedParts(manifest) : [], [manifest]);
  const active = parts[activePartIndex];
  const usesGaplessSource = parts.length > 1 && !offlinePlayback;

  useEffect(() => {
    if (parts.length <= 1 || offlinePlayback) {
      setGaplessSourceUrl(null);
      return;
    }
    return prepareGaplessPlayback(
      parts.map((positioned) => positioned.part),
      setGaplessSourceUrl,
      () => setUnavailable(true)
    );
  }, [parts, offlinePlayback]);

  useEffect(() => {
    if (!offlinePlayback || !offlineCapability || !offlineCopy || !active) {
      setOfflineSourceUrl(null);
      return;
    }
    let disposed = false;
    let objectUrl: string | undefined;
    void offlineCapability.openPart({
      audiobookId: offlineCopy.audiobookId,
      assetVersionId: offlineCopy.assetVersionId,
      partId: active.part.partId
    }).then((blob) => {
      if (disposed) return;
      objectUrl = URL.createObjectURL(blob);
      setOfflineSourceUrl(objectUrl);
    }).catch(() => setUnavailable(true));
    return () => {
      disposed = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [offlinePlayback, offlineCapability, offlineCopy, active?.part.partId]);

  const persist = (nextPosition: number) => {
    if (!manifest) return;
    void updatePlaybackPosition(
      manifest.audiobookId,
      manifest.assetVersionId,
      Math.min(Math.max(nextPosition, 0), manifest.totalDurationMs),
      resumeVersionRef.current,
      csrf
    ).then((saved) => {
      resumeVersionRef.current = saved.version;
      setResumeVersion(saved.version);
    }).catch(() => undefined);
  };

  const seekTo = (nextPosition: number) => {
    if (!manifest || parts.length === 0) return;
    const bounded = Math.min(Math.max(nextPosition, 0), manifest.totalDurationMs);
    const nextIndex = partIndexAt(parts, bounded);
    const audio = audioRef.current;
    setPositionMs(bounded);
    if (usesGaplessSource && audio) {
      setActivePartIndex(nextIndex);
      audio.currentTime = bounded / 1000;
    } else if (nextIndex !== activePartIndex) {
      setActivePartIndex(nextIndex);
    } else if (audio) {
      audio.currentTime = Math.max(0, (bounded - parts[nextIndex].startMs) / 1000);
    }
  };

  const previousChapter = () => {
    if (!manifest || !active) return;
    const current = active.chapter.ordinal;
    const target = manifest.chapters.find((chapter) => chapter.ordinal === Math.max(0, current - 1));
    if (target) seekTo(target.startMs);
  };

  const nextChapter = () => {
    if (!manifest || !active) return;
    const target = manifest.chapters.find((chapter) => chapter.ordinal === active.chapter.ordinal + 1);
    if (target) seekTo(target.startMs);
  };

  useEffect(() => {
    if (!manifest || !active || !("mediaSession" in navigator)) return;
    navigator.mediaSession.metadata = new MediaMetadata({
      title: active.chapter.title,
      artist: manifest.narratorVoice,
      album: "Private Audiobook"
    });
    navigator.mediaSession.setActionHandler("play", () => void audioRef.current?.play());
    navigator.mediaSession.setActionHandler("pause", () => audioRef.current?.pause());
    navigator.mediaSession.setActionHandler("previoustrack", previousChapter);
    navigator.mediaSession.setActionHandler("nexttrack", nextChapter);
    navigator.mediaSession.setActionHandler("seekto", (details) => {
      if (details.seekTime !== undefined) seekTo(details.seekTime * 1000);
    });
    return () => {
      for (const action of ["play", "pause", "previoustrack", "nexttrack", "seekto"] as MediaSessionAction[]) {
        navigator.mediaSession.setActionHandler(action, null);
      }
    };
  }, [manifest, activePartIndex, usesGaplessSource]);

  if (playable.length === 0) return null;

  return (
    <section className="private-player" aria-label="Private Audiobook player">
      {playable.length > 1 && (
        <div className="audiobook-switcher" aria-label="Finalized audiobooks">
          {playable.map((candidate, index) => (
            <Button
              key={candidate.privateAudiobook!.audiobookId}
              type="button"
              variant="outline"
              aria-pressed={candidate.privateAudiobook!.audiobookId === selected?.privateAudiobook?.audiobookId}
              onClick={() => setSelectedId(candidate.privateAudiobook!.audiobookId)}
            >
              Open audiobook {index + 1}
            </Button>
          ))}
        </div>
      )}
      {unavailable && <p role="alert">This Private Audiobook is not available for playback.</p>}
      {!manifest && !unavailable && <p aria-live="polite">Opening your Private Audiobook…</p>}
      {manifest && active && !unavailable && (
        <>
          <div className="private-player-heading">
            <span className="empty-mark" aria-hidden="true"><BookOpen size={28} /></span>
            <div>
              <span className="card-kicker">Chapter {active.chapter.ordinal + 1} of {manifest.chapters.length}</span>
              <h2>{active.chapter.title}</h2>
              <p>{publicationLabel(manifest.sourceMediaType)} · Narrated by {manifest.narratorVoice}</p>
            </div>
          </div>

          {offlineCapability && (
            <OfflineCopyControls
              audiobookId={manifest.audiobookId}
              assetVersionId={manifest.assetVersionId}
              capability={offlineCapability}
              installed={isInstalledPwa()}
              onPlay={(copy) => {
                audioRef.current?.pause();
                setOfflineCopy(copy);
                setOfflinePlayback(true);
              }}
            />
          )}

          <input
            className="audiobook-position"
            type="range"
            min="0"
            max={manifest.totalDurationMs}
            step="1000"
            value={Math.min(positionMs, manifest.totalDurationMs)}
            aria-label="Audiobook position"
            onChange={(event) => seekTo(Number(event.target.value))}
          />
          <div className="player-times">
            <span>{formatTime(positionMs)}</span>
            <span>{formatTime(manifest.totalDurationMs)}</span>
          </div>

          <div className="private-player-controls">
            <Button type="button" variant="ghost" size="icon" onClick={previousChapter} aria-label="Previous chapter">
              <SkipBack size={18} />
            </Button>
            <Button
              type="button"
              size="icon"
              disabled={offlinePlayback ? !offlineSourceUrl : usesGaplessSource && !gaplessSourceUrl}
              aria-label={`${isPlaying ? "Pause" : "Play"} ${active.chapter.title}`}
              onClick={() => {
                const audio = audioRef.current;
                if (!audio) return;
                if (audio.paused) {
                  void audio.play()
                    .then(() => setIsPlaying(true))
                    .catch(() => setIsPlaying(false));
                } else {
                  audio.pause();
                }
              }}
            >
              {isPlaying ? <Pause size={18} fill="currentColor" /> : <Play size={18} fill="currentColor" />}
            </Button>
            <Button type="button" variant="ghost" size="icon" onClick={nextChapter} aria-label="Next chapter">
              <SkipForward size={18} />
            </Button>
            <label className="playback-speed">
              <span>Playback speed</span>
              <select
                aria-label="Playback speed"
                value={speed}
                onChange={(event) => {
                  const nextSpeed = Number(event.target.value);
                  setSpeed(nextSpeed);
                  if (audioRef.current) audioRef.current.playbackRate = nextSpeed;
                }}
              >
                {[0.75, 1, 1.25, 1.5, 2].map((value) => <option key={value} value={value}>{value}×</option>)}
              </select>
            </label>
          </div>

          <nav className="chapter-list" aria-label="Audiobook chapters">
            {manifest.chapters.map((chapter) => (
              <button
                type="button"
                key={chapter.chapterId}
                aria-current={chapter.chapterId === active.chapter.chapterId ? "true" : undefined}
                onClick={() => seekTo(chapter.startMs)}
              >
                <span>{chapter.ordinal + 1}</span>
                <strong>{chapter.title}</strong>
                <small>{formatTime(chapter.durationMs)}</small>
              </button>
            ))}
          </nav>

          <audio
            ref={audioRef}
            src={offlinePlayback
              ? offlineSourceUrl ?? undefined
              : usesGaplessSource ? gaplessSourceUrl ?? undefined : active.part.mediaUrl}
            preload="metadata"
            onLoadedMetadata={(event) => {
              event.currentTarget.currentTime = usesGaplessSource
                ? positionMs / 1000
                : Math.max(0, (positionMs - active.startMs) / 1000);
              event.currentTarget.playbackRate = speed;
              if (continueOfflineRef.current) {
                continueOfflineRef.current = false;
                void event.currentTarget.play();
              }
            }}
            onTimeUpdate={(event) => {
              const nextPosition = Math.min(
                usesGaplessSource
                  ? event.currentTarget.currentTime * 1000
                  : active.startMs + event.currentTarget.currentTime * 1000,
                manifest.totalDurationMs
              );
              setPositionMs(nextPosition);
              if (usesGaplessSource) setActivePartIndex(partIndexAt(parts, nextPosition));
            }}
            onPlay={() => setIsPlaying(true)}
            onPause={() => {
              setIsPlaying(false);
              if (!offlinePlayback) persist(positionMs);
            }}
            onEnded={() => {
              if (offlinePlayback && activePartIndex + 1 < parts.length) {
                continueOfflineRef.current = true;
                setActivePartIndex(activePartIndex + 1);
                setPositionMs(parts[activePartIndex + 1].startMs);
                return;
              }
              setPositionMs(manifest.totalDurationMs);
              setIsPlaying(false);
              if (!offlinePlayback) persist(manifest.totalDurationMs);
            }}
          />
          <span className="resume-status" aria-live="polite">Resume position version {resumeVersion}</span>
        </>
      )}
    </section>
  );
}

function positionedParts(manifest: PlaybackManifest): PositionedPart[] {
  return manifest.chapters.flatMap((chapter) => {
    let nextStart = chapter.startMs;
    return chapter.parts.map((part) => {
      const positioned = { chapter, part, startMs: nextStart };
      nextStart += part.durationMs;
      return positioned;
    });
  });
}

function partIndexAt(parts: PositionedPart[], positionMs: number): number {
  const found = parts.findIndex((candidate) =>
    positionMs >= candidate.startMs
    && positionMs < candidate.startMs + candidate.part.durationMs
  );
  return found >= 0 ? found : Math.max(0, parts.length - 1);
}

function publicationLabel(mediaType: string): string {
  return mediaType === "application/pdf" ? "PDF publication" : "EPUB publication";
}

function formatTime(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return hours > 0
    ? `${hours}:${minutes.toString().padStart(2, "0")}:${seconds.toString().padStart(2, "0")}`
    : `${minutes}:${seconds.toString().padStart(2, "0")}`;
}
