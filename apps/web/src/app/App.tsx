import { LogOut, Moon, Sun } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "../ui";
import {
  CsrfForm,
  SignInDialog,
  fetchIdentitySession,
  type IdentitySession
} from "../session";
import {
  LibraryLoading,
  PrivateLibrary,
  fetchLibrary,
  type Library
} from "../library";
import {
  ManagedOfflineLibrary,
  browserSupportsManagedOfflineCopies,
  createBrowserOfflineCopyManager,
  createOfflinePlaybackManager,
  type OfflineCopyCapability,
  type OfflineCopyRecord
} from "../offline-copy";
import { monitorConnectedPrivateAccess, resolvePrivateAccess } from "./private-access";
import {
  PublicStudio,
  fetchPlatformStatus,
  type PlatformStatus
} from "../public-studio";
import {
  fetchActionQueue,
  fetchListenerAccess,
  SupportAccessActivity,
  TrustOperationsDesk,
  type ActionQueue,
  type ListenerAccessSummary
} from "../trust-operations";

type Theme = "light" | "dark";

function App() {
  const [theme, setTheme] = useState<Theme>(() =>
    window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"
  );
  const [platformStatus, setPlatformStatus] = useState<PlatformStatus | null>(null);
  const [statusDelayed, setStatusDelayed] = useState(false);
  const [identitySession, setIdentitySession] = useState<IdentitySession | null>(null);
  const [library, setLibrary] = useState<Library | null>(null);
  const [actionQueue, setActionQueue] = useState<ActionQueue | null>(null);
  const [listenerAccess, setListenerAccess] = useState<ListenerAccessSummary | null>(null);
  const [offlineLibrary, setOfflineLibrary] = useState<{
    records: OfflineCopyRecord[];
    capability: OfflineCopyCapability;
  } | null>(null);
  const [signOutError, setSignOutError] = useState(false);
  const [offlineAccessNotice, setOfflineAccessNotice] = useState<string>();
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
    const offlineCapable = browserSupportsManagedOfflineCopies();
    let refreshing = false;
    const loadPrivateAccess = async () => {
      if (refreshing) return;
      refreshing = true;
      try {
        const result = await resolvePrivateAccess(controller.signal, {
          offlineCapable,
          fetchSession: fetchIdentitySession,
          fetchLibrary,
          playbackManager: createOfflinePlaybackManager,
          connectedManager: createBrowserOfflineCopyManager
        });
        setIdentitySession(result.session ?? null);
        setLibrary(result.library ?? null);
        setOfflineLibrary(result.offline ?? null);
        if (result.session?.authenticated) {
          void fetchListenerAccess(controller.signal).then(setListenerAccess).catch(() => undefined);
          if (result.session.staff?.roles.length) {
            void fetchActionQueue(controller.signal).then(setActionQueue).catch(() => undefined);
          }
        }
        if (result.evictedCount > 0) {
          setOfflineAccessNotice(
            `${result.evictedCount} Offline ${result.evictedCount === 1 ? "Copy was" : "Copies were"} removed because access changed.`
          );
        }
      } finally {
        refreshing = false;
      }
    };
    void loadPrivateAccess().catch(() => undefined);
    const reconnect = async () => {
      await loadPrivateAccess();
    };
    const stopMonitoring = monitorConnectedPrivateAccess(reconnect);
    return () => {
      controller.abort();
      stopMonitoring();
    };
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
            <CsrfForm
              action="/api/v1/auth/logout"
              csrf={identitySession.csrf}
              className="inline-form"
              beforeSubmit={async () => {
                setSignOutError(false);
                if (browserSupportsManagedOfflineCopies()) {
                  await createOfflinePlaybackManager().purgeAll();
                }
              }}
              onSubmitError={() => setSignOutError(true)}
            >
              <Button variant="outline" type="submit"><LogOut size={16} /> Sign out</Button>
            </CsrfForm>
          ) : (
            <Button variant="outline" type="button" onClick={() => setSignInOpen(true)}>Sign in</Button>
          )}
        </nav>
      </header>

      <main id="top">
        {signOutError && <p className="global-alert" role="alert">Offline Copy keys could not be purged. Sign out was stopped.</p>}
        {offlineAccessNotice && <p className="global-alert" role="status">{offlineAccessNotice}</p>}
        {authenticated ? (
          <>
            {actionQueue && <TrustOperationsDesk
              queue={actionQueue}
              csrf={identitySession.csrf}
              staffRoles={identitySession.staff?.roles ?? []}
            />}
            {library ? <PrivateLibrary library={library} csrf={identitySession.csrf} /> : <LibraryLoading />}
            {listenerAccess && <SupportAccessActivity summary={listenerAccess} csrf={identitySession.csrf} />}
          </>
        ) : offlineLibrary ? (
          <ManagedOfflineLibrary records={offlineLibrary.records} capability={offlineLibrary.capability} />
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

export default App;
