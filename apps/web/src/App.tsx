import {
  ArrowRight,
  BookOpen,
  Headphones,
  Library as LibraryIcon,
  LogOut,
  Moon,
  Pause,
  Play,
  Plus,
  ShieldCheck,
  Sparkles,
  Sun,
  Upload
} from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button } from "./components/ui/button";
import {
  fetchIdentitySession,
  fetchLibrary,
  type CsrfProof,
  type IdentitySession,
  type Library
} from "./identity-session";
import { fetchPlatformStatus, type PlatformStatus } from "./platform-status";
import { submitAuthorizedEpub, type Submission, type TransferStage } from "./publication-submission";
import {
  confirmGenerationRecipe,
  fetchVoiceCatalog,
  type ConfirmedGenerationRecipe,
  type NarrationPace,
  type NarratorVoice,
  type VoiceCatalog
} from "./narration-selection";

type Theme = "light" | "dark";

const providers = ["google", "apple", "facebook"] as const;
const waveform = [28, 44, 62, 35, 76, 52, 82, 43, 68, 89, 56, 37, 72, 48, 64, 31, 54, 78, 42, 58, 34, 69, 46, 29];

function App() {
  const [theme, setTheme] = useState<Theme>(() =>
    window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"
  );
  const [platformStatus, setPlatformStatus] = useState<PlatformStatus | null>(null);
  const [statusDelayed, setStatusDelayed] = useState(false);
  const [identitySession, setIdentitySession] = useState<IdentitySession | null>(null);
  const [library, setLibrary] = useState<Library | null>(null);
  const [signInOpen, setSignInOpen] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);
  const audioRef = useRef<HTMLAudioElement>(null);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  useEffect(() => {
    const controller = new AbortController();
    fetchPlatformStatus(controller.signal)
      .then(setPlatformStatus)
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) setStatusDelayed(true);
      });
    fetchIdentitySession(controller.signal)
      .then((session) => {
        setIdentitySession(session);
        if (session.authenticated) return fetchLibrary(controller.signal).then(setLibrary);
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

  const toggleSample = () => {
    const audio = audioRef.current;
    if (!audio) return;
    if (audio.paused) {
      void audio.play().then(() => setIsPlaying(true)).catch(() => setIsPlaying(false));
    } else {
      audio.pause();
      setIsPlaying(false);
    }
  };

  const authenticated = identitySession?.authenticated === true;

  return (
    <div className="app-shell">
      <header className="site-header">
        <a className="brand" href="#top" aria-label="Folio home">
          <span className="brand-mark" aria-hidden="true">F</span>
          <span>Folio</span>
        </a>
        <nav aria-label="Primary navigation">
          {!authenticated && <a href="#sample">Sample</a>}
          {!authenticated && <a href="#privacy">Privacy</a>}
          <Button variant="ghost" size="icon" type="button" onClick={() => setTheme(theme === "light" ? "dark" : "light")} aria-label={`Switch to ${theme === "light" ? "dark" : "light"} mode`}>
            {theme === "light" ? <Moon size={18} /> : <Sun size={18} />}
          </Button>
          {authenticated ? (
            <CsrfForm action="/api/v1/auth/logout" csrf={identitySession.csrf} className="inline-form">
              <Button variant="outline" type="submit"><LogOut size={16} /> Sign out</Button>
            </CsrfForm>
          ) : (
            <Button variant="outline" type="button" onClick={() => setSignInOpen(true)}>Sign in</Button>
          )}
        </nav>
      </header>

      <main id="top">
        {authenticated ? (
          library ? <PrivateLibrary library={library} csrf={identitySession.csrf} /> : <LibraryLoading />
        ) : (
          <PublicStudio
            audioRef={audioRef}
            isPlaying={isPlaying}
            onToggleSample={toggleSample}
            onSampleEnded={() => setIsPlaying(false)}
            platformStatus={platformStatus}
            statusDelayed={statusDelayed}
          />
        )}
      </main>

      <footer>
        <span>© 2026 Folio</span>
        <span>Authorized publications only. Offline copies are managed access, not DRM.</span>
      </footer>

      {signInOpen && (
        <SignInDialog csrf={identitySession?.csrf} onClose={() => setSignInOpen(false)} />
      )}
    </div>
  );
}

function PublicStudio({
  audioRef,
  isPlaying,
  onToggleSample,
  onSampleEnded,
  platformStatus,
  statusDelayed
}: {
  audioRef: React.RefObject<HTMLAudioElement | null>;
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
            <span className="sample-author">Narrated by Clara · Natural pace</span>
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

function PrivateLibrary({ library, csrf }: { library: Library; csrf: CsrfProof }) {
  const [methodsOpen, setMethodsOpen] = useState(false);
  const [creationOpen, setCreationOpen] = useState(false);
  const availableProviders = providers.filter((provider) => !library.signInMethods.includes(provider));
  const canStartConversion = library.conversionEntitlement.canStartConversion;
  return (
    <section className="library-studio" aria-labelledby="library-title">
      <div className="library-heading">
        <div>
          <div className="eyebrow"><LibraryIcon size={15} /> Private studio</div>
          <h1 id="library-title">Your Library</h1>
          <p>Welcome back, <strong>{library.displayName}</strong>.</p>
        </div>
        <Button disabled={!canStartConversion} onClick={() => setCreationOpen(true)}><Upload size={17} /> Create audiobook</Button>
      </div>

      <div className="library-grid">
        {library.audiobooks.length === 0 ? (
          <article className="empty-library">
            <span className="empty-mark" aria-hidden="true"><BookOpen size={28} /></span>
            <h2>Your first audiobook will live here</h2>
            <p>Choose an authorized PDF or DRM-free EPUB when you’re ready. Nothing leaves your device until you confirm.</p>
            <Button disabled={!canStartConversion} onClick={() => setCreationOpen(true)}><Plus size={17} /> Start a private audiobook</Button>
          </article>
        ) : (
          <div className="conversion-list" aria-label="Private audiobooks">
            {library.audiobooks.map((conversion) => (
              <article className="preparing-audiobook" key={conversion.conversionId}>
                <span className="empty-mark" aria-hidden="true"><Sparkles size={28} /></span>
                <span className="card-kicker">{conversion.state}</span>
                <h2>Preparing your private audiobook</h2>
                <p>The publication passed quarantine inspection. Folio is preparing its narration plan.</p>
              </article>
            ))}
          </div>
        )}

        <div className="library-sidebar">
          <EntitlementCard entitlement={library.conversionEntitlement} />
          <aside className="identity-card" aria-labelledby="identity-title">
            <span className="card-kicker">Listener Identity</span>
            <h2 id="identity-title">{library.displayName}</h2>
            {library.contactEmail && <p>{library.contactEmail}</p>}
            <div className="method-list" aria-label="Linked sign-in methods">
              {library.signInMethods.map((method) => <span key={method}>{providerLabel(method)} <ShieldCheck size={14} /></span>)}
            </div>
            {availableProviders.length > 0 && (
              <>
                <Button type="button" variant="outline" onClick={() => setMethodsOpen(!methodsOpen)}>
                  <Plus size={16} /> Add sign-in method
                </Button>
                {methodsOpen && (
                  <div className="link-methods">
                    <p>Fresh authentication of both methods is required.</p>
                    {availableProviders.map((provider) => (
                      <CsrfForm key={provider} action={`/api/v1/auth/links/${provider}`} csrf={csrf}>
                        <Button type="submit" variant="ghost">Add {providerLabel(provider)}</Button>
                      </CsrfForm>
                    ))}
                  </div>
                )}
              </>
            )}
          </aside>
        </div>
      </div>
      {creationOpen && <CreateAudiobookDialog csrf={csrf} onClose={() => setCreationOpen(false)} />}
    </section>
  );
}

function CreateAudiobookDialog({ csrf, onClose }: { csrf: CsrfProof; onClose: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [attested, setAttested] = useState(false);
  const [stage, setStage] = useState<TransferStage | "CONFIRMING" | "PREPARING" | "FAILED" | null>(null);
  const [submission, setSubmission] = useState<Submission | null>(null);
  const [catalog, setCatalog] = useState<VoiceCatalog | null>(null);
  const [selectedVoiceId, setSelectedVoiceId] = useState<string | null>(null);
  const [pace, setPace] = useState<NarrationPace>("NATURAL");
  const [recipe, setRecipe] = useState<ConfirmedGenerationRecipe | null>(null);
  const [previewPlaying, setPreviewPlaying] = useState<string | null>(null);
  const previewAudioRef = useRef<HTMLAudioElement>(null);
  const busy = stage !== null && stage !== "PREPARING" && stage !== "FAILED";

  useEffect(() => {
    const controller = new AbortController();
    fetchVoiceCatalog(controller.signal)
      .then((loaded) => {
        setCatalog(loaded);
        setPace(loaded.defaultPace);
        setSelectedVoiceId(loaded.voices.find((voice) => voice.availability === "AVAILABLE")?.id ?? null);
      })
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) setStage("FAILED");
      });
    return () => {
      controller.abort();
      previewAudioRef.current?.pause();
    };
  }, []);

  const choose = (candidate?: File) => {
    if (!candidate) return;
    const epub = candidate.name.toLowerCase().endsWith(".epub")
      && (candidate.type === "" || candidate.type === "application/epub+zip")
      && candidate.size > 0
      && candidate.size <= 262_144_000;
    setFile(epub ? candidate : null);
    if (!epub) setStage("FAILED");
  };

  const create = async () => {
    if (!file || !attested || !selectedVoiceId || busy) return;
    setSubmission(null);
    setRecipe(null);
    try {
      const result = await submitAuthorizedEpub(file, csrf, setStage);
      setSubmission(result);
      if (result.state !== "ADMITTED" || !result.conversionId) {
        setStage("FAILED");
        return;
      }
      setStage("CONFIRMING");
      const confirmed = await confirmGenerationRecipe(result.conversionId, selectedVoiceId, pace, csrf);
      setRecipe(confirmed);
      setStage("PREPARING");
    } catch {
      setStage("FAILED");
    }
  };

  const togglePreview = (voice: NarratorVoice) => {
    const audio = previewAudioRef.current;
    if (!audio) return;
    if (previewPlaying === voice.id) {
      audio.pause();
      setPreviewPlaying(null);
      return;
    }
    audio.pause();
    audio.src = voice.preview.uri;
    void audio.play()
      .then(() => setPreviewPlaying(voice.id))
      .catch(() => setPreviewPlaying(null));
  };

  const selectedVoice = catalog?.voices.find((voice) => voice.id === selectedVoiceId);

  return (
    <div className="dialog-backdrop" role="presentation" onMouseDown={busy ? undefined : onClose}>
      <section className="create-dialog" role="dialog" aria-modal="true" aria-labelledby="create-title" onMouseDown={(event) => event.stopPropagation()}>
        <button className="dialog-close" type="button" onClick={onClose} disabled={busy} aria-label="Close Create audiobook">×</button>
        <span className="card-kicker">Private creation flow</span>
        <h2 id="create-title">Create a private audiobook</h2>
        {stage === "PREPARING" ? (
          <div className="creation-result" aria-live="polite">
            <Sparkles size={24} />
            <h3>Preparing your private audiobook</h3>
            <p>
              The EPUB passed quarantine inspection. {recipe?.voiceDisplayName} at {paceLabel(recipe?.pace ?? pace)} pace
              is frozen for generation while Folio prepares its narration plan.
            </p>
            <span>Conversion {submission?.conversionId?.slice(0, 8)}</span>
          </div>
        ) : (
          <>
            <label
              className="epub-dropzone"
              onDragOver={(event) => event.preventDefault()}
              onDrop={(event) => {
                event.preventDefault();
                choose(event.dataTransfer.files[0]);
              }}
            >
              <Upload size={24} />
              <strong>Choose DRM-free EPUB</strong>
              <span>or drop one file here · up to 250 MiB</span>
              <input
                type="file"
                accept=".epub,application/epub+zip"
                onChange={(event) => choose(event.target.files?.[0])}
                disabled={busy}
              />
            </label>
            {file && <p className="local-file">{file.name} stays on this device until you activate Create audiobook.</p>}
            <section className="narrator-choice" aria-labelledby="narrator-choice-title">
              <div className="choice-heading">
                <div>
                  <h3 id="narrator-choice-title">Choose a Narrator Voice</h3>
                  <p>Platform-owned names keep the underlying provider private.</p>
                </div>
                {!catalog && stage !== "FAILED" && <span>Opening voice library…</span>}
              </div>
              {catalog && (
                <div className="voice-choice-grid">
                  {catalog.voices.map((voice) => {
                    const available = voice.availability === "AVAILABLE";
                    const selected = selectedVoiceId === voice.id;
                    const playing = previewPlaying === voice.id;
                    return (
                      <article className={`voice-choice-card${selected ? " voice-choice-card--selected" : ""}`} key={voice.id}>
                        <button
                          className="voice-preview"
                          type="button"
                          aria-label={`${playing ? "Pause" : "Play"} ${voice.displayName} preview`}
                          onClick={() => togglePreview(voice)}
                          disabled={!available || busy}
                        >
                          {playing ? <Pause size={15} fill="currentColor" /> : <Play size={15} fill="currentColor" />}
                        </button>
                        <button
                          className="voice-select"
                          type="button"
                          aria-label={`Select ${voice.displayName}`}
                          aria-pressed={selected}
                          onClick={() => setSelectedVoiceId(voice.id)}
                          disabled={!available || busy}
                        >
                          <strong>{voice.displayName}</strong>
                          <span>{voice.englishVariety}</span>
                          <small>{voice.descriptors.join(" · ")}</small>
                        </button>
                        <small className="preview-note">
                          {voice.preview.durationSeconds}-second standard passage · AI-generated
                        </small>
                        {!available && <small className="voice-availability">{availabilityLabel(voice.availability)}</small>}
                      </article>
                    );
                  })}
                </div>
              )}
              <audio ref={previewAudioRef} onEnded={() => setPreviewPlaying(null)} preload="none" />
            </section>
            <fieldset className="pace-choice" disabled={busy || !catalog}>
              <legend>Narration Pace</legend>
              <div role="group" aria-label="Narration Pace">
                {(catalog?.paces ?? ["MEASURED", "NATURAL", "BRISK"]).map((candidate) => (
                  <button
                    key={candidate}
                    type="button"
                    aria-pressed={pace === candidate}
                    onClick={() => setPace(candidate)}
                  >
                    {paceLabel(candidate)}
                  </button>
                ))}
              </div>
              <p>Audiobook-wide narration intent; playback speed stays independent.</p>
            </fieldset>
            <label className="attestation-check">
              <input type="checkbox" checked={attested} onChange={(event) => setAttested(event.target.checked)} disabled={busy} />
              <span>I attest that I’m permitted to reproduce this DRM-free publication as a private audiobook.</span>
            </label>
            <p className="rights-note">Rights Attestation · terms rights-v1 · notice notice-v1</p>
            {stage && <p className={`creation-status creation-status--${stage.toLowerCase()}`} aria-live="polite">{stageLabel(stage)}</p>}
            <Button type="button" onClick={() => void create()} disabled={!file || !attested || !selectedVoice || busy}>
              <Upload size={17} /> Create audiobook
            </Button>
          </>
        )}
      </section>
    </div>
  );
}

function paceLabel(pace: NarrationPace): string {
  return pace.charAt(0) + pace.slice(1).toLowerCase();
}

function availabilityLabel(availability: NarratorVoice["availability"]): string {
  if (availability === "TEMPORARILY_UNAVAILABLE") return "Temporarily unavailable";
  if (availability === "RETIRED") return "Retired";
  return "Available";
}

function stageLabel(stage: TransferStage | "CONFIRMING" | "FAILED"): string {
  if (stage === "HASHING") return "Checking the EPUB locally…";
  if (stage === "UPLOADING") return "Transferring to private quarantine…";
  if (stage === "INSPECTING") return "Inspecting the DRM-free EPUB…";
  if (stage === "CONFIRMING") return "Freezing your exact Narrator Voice and Narration Pace…";
  return "This EPUB could not be submitted. Check the file and try again.";
}

function EntitlementCard({ entitlement }: { entitlement: Library["conversionEntitlement"] }) {
  return (
    <aside className={`entitlement-card entitlement-card--${entitlement.canStartConversion ? "available" : "denied"}`} aria-labelledby="entitlement-title">
      <span className="card-kicker">Conversion Entitlement</span>
      <h2 id="entitlement-title">
        {entitlement.canStartConversion
          ? `${entitlement.availableCharacters.toLocaleString("en-US")} narratable characters available`
          : "No free Conversion Entitlement is available yet"}
      </h2>
      <p>
        {entitlement.canStartConversion
          ? "A bounded allowance and provider-cost ceiling are reserved when you activate Create audiobook."
          : "A conversion can start after an approved free grant is added to your Listener Identity."}
      </p>
    </aside>
  );
}

function LibraryLoading() {
  return <section className="library-studio library-loading" aria-live="polite"><span>Opening your private Library…</span></section>;
}

function SignInDialog({ csrf, onClose }: { csrf?: CsrfProof; onClose: () => void }) {
  return (
    <div className="dialog-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="sign-in-dialog" role="dialog" aria-modal="true" aria-labelledby="sign-in-title" onMouseDown={(event) => event.stopPropagation()}>
        <button className="dialog-close" type="button" onClick={onClose} aria-label="Close sign-in methods">×</button>
        <span className="card-kicker">EU-hosted secure broker</span>
        <h2 id="sign-in-title">Choose a sign-in method</h2>
        <p>ZITADEL verifies your provider and requires TOTP before Folio opens your private Library.</p>
        <div className="provider-list">
          {providers.map((provider) => (
            <Button asChild variant="outline" key={provider}>
              <a href={`/oauth2/authorization/${provider}`}>Continue with {providerLabel(provider)}</a>
            </Button>
          ))}
        </div>
        {csrf && (
          <CsrfForm action="/api/v1/auth/recovery" csrf={csrf}>
            <button className="recovery-link" type="submit">Recover access securely</button>
          </CsrfForm>
        )}
        <small>Email is contact metadata only. Matching email addresses never merge Listener identities.</small>
      </section>
    </div>
  );
}

function CsrfForm({ action, csrf, className, children }: { action: string; csrf: CsrfProof; className?: string; children: ReactNode }) {
  return (
    <form action={action} method="post" className={className}>
      <input type="hidden" name={csrf.parameterName} value={csrf.token} />
      {children}
    </form>
  );
}

function providerLabel(provider: string) {
  return provider.charAt(0).toUpperCase() + provider.slice(1);
}

export default App;
