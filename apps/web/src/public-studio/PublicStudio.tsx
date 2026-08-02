import { ArrowRight, BookOpen, Headphones, Pause, Play, ShieldCheck, Sparkles } from "lucide-react";
import type { RefObject } from "react";
import { Button } from "../ui";
import type { PlatformStatus } from "./platform-status";

const waveform = [28, 44, 62, 35, 76, 52, 82, 43, 68, 89, 56, 37, 72, 48, 64, 31, 54, 78, 42, 58, 34, 69, 46, 29];

export function PublicStudio({
  audioRef,
  isPlaying,
  onToggleSample,
  onSampleEnded,
  platformStatus,
  statusDelayed
}: {
  audioRef: RefObject<HTMLAudioElement | null>;
  isPlaying: boolean;
  onToggleSample: () => void;
  onSampleEnded: () => void;
  platformStatus: PlatformStatus | null;
  statusDelayed: boolean;
}) {
  const online = platformStatus?.availability.core === "AVAILABLE" && platformStatus.availability.database === "AVAILABLE";
  return (
    <>
      <section className="hero" aria-labelledby="hero-title">
        <div className="hero-copy">
          <div className="eyebrow"><Sparkles size={15} /> Private AI narration studio</div>
          <h1 id="hero-title">Stories deserve <span className="title-break">to be <em>heard.</em></span></h1>
          <p className="hero-intro">
            Turn publications you’re allowed to reproduce into thoughtfully structured,
            beautifully narrated audiobooks—kept private from first page to final chapter.
          </p>
          <div className="hero-actions">
            <Button>Request an invitation <ArrowRight size={17} /></Button>
            <a href="#sample">Hear the difference <Headphones size={17} /></a>
          </div>
          <div className="trust-row" id="privacy">
            <span><ShieldCheck size={17} /> Private by design</span>
            <span><BookOpen size={17} /> PDF + DRM-free EPUB</span>
          </div>
        </div>

        <article className="sample-card" id="sample" aria-labelledby="sample-title">
          <div className="sample-art" aria-hidden="true">
            <div className="moon-disc" />
            <div className="book-lines"><i /><i /><i /><i /></div>
            <span className="sample-kicker">A Folio original</span>
            <strong>The Midnight Library<br />of Small Beginnings</strong>
            <span className="sample-author">Narrated by Callum · Natural pace</span>
          </div>
          <div className="player">
            <div className="player-heading">
              <div>
                <span>Public sample · 00:27</span>
                <h2 id="sample-title">Chapter one, “A light left on”</h2>
              </div>
              <Button size="icon" onClick={onToggleSample} aria-label={`${isPlaying ? "Pause" : "Play"} public sample`}>
                {isPlaying ? <Pause fill="currentColor" size={18} /> : <Play fill="currentColor" size={18} />}
              </Button>
            </div>
            <div className="waveform" aria-hidden="true">
              {waveform.map((height, index) => <i key={index} style={{ height: `${height}%` }} />)}
            </div>
            <div className="player-times"><span>0:00</span><span>0:27</span></div>
            <audio ref={audioRef} src="/samples/midnight-library-of-small-beginnings.mp3" onEnded={onSampleEnded} preload="metadata" />
            <details className="transcript">
              <summary>Read transcript</summary>
              <p>
                At midnight, the little library kept one lamp burning. Its warm circle of light
                rested on a book no one remembered shelving. When Mara opened the cover, the
                first page whispered: every beginning is small enough to hold in one hand. She
                carried the book to the window, where rain made silver paths across the glass.
                With each page, the quiet room seemed to breathe around her. Somewhere beyond
                the shelves, a clock struck one, and the story waited patiently for her to turn
                the page.
              </p>
            </details>
          </div>
        </article>
      </section>

      <section className="platform-strip" aria-label="Platform availability">
        <div className={`status-dot ${online ? "status-dot--online" : ""}`} aria-hidden="true" />
        <div>
          <strong>{statusDelayed ? "Sample ready · Status delayed" : online ? "Core online · Sample ready" : "Checking platform…"}</strong>
          <span>{platformStatus ? `Build ${platformStatus.build.version} · ${platformStatus.build.revision}` : "Content-free availability from the same-origin core"}</span>
        </div>
        <span className="status-label">{platformStatus?.apiVersion ?? "v1"} API</span>
      </section>
    </>
  );
}
