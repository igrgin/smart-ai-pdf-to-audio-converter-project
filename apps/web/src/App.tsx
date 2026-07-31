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
        <Button disabled={!canStartConversion}><Upload size={17} /> Create audiobook</Button>
      </div>

      <div className="library-grid">
        <article className="empty-library">
          <span className="empty-mark" aria-hidden="true"><BookOpen size={28} /></span>
          <h2>Your first audiobook will live here</h2>
          <p>Choose an authorized PDF or DRM-free EPUB when you’re ready. Nothing leaves your device until you confirm.</p>
          <Button disabled={!canStartConversion}><Plus size={17} /> Start a private audiobook</Button>
        </article>

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
    </section>
  );
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
          ? "Your allowance is reserved only after the narration plan is measured."
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
