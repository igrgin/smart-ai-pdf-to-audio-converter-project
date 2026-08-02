import { BookOpen, Library as LibraryIcon, Plus, ShieldCheck, Trash2, Upload } from "lucide-react";
import { useState } from "react";
import { CreateAudiobookDialog } from "../audiobook-creation";
import { browserSupportsManagedOfflineCopies, createOfflinePlaybackManager } from "../offline-copy";
import { PrivateAudiobookPlayer } from "../playback";
import { CsrfForm, type CsrfProof } from "../session";
import { Button } from "../ui";
import {
  deleteListenerAccount,
  deletePrivateAudiobook,
  type AudiobookConversion,
  type DeletionReceipt,
  type Library
} from "./api";
import { ConversionCard } from "./components/ConversionCard";
import { EntitlementCard } from "./components/EntitlementCard";

const providers = ["google", "apple", "facebook"] as const;

export function PrivateLibrary({ library, csrf }: { library: Library; csrf: CsrfProof }) {
  const [methodsOpen, setMethodsOpen] = useState(false);
  const [creationTarget, setCreationTarget] = useState<AudiobookConversion | null | undefined>(undefined);
  const [deletingAudiobooks, setDeletingAudiobooks] = useState<Record<string, DeletionReceipt>>({});
  const [accountDeletion, setAccountDeletion] = useState<DeletionReceipt>();
  const [deletionError, setDeletionError] = useState<string>();
  const availableProviders = providers.filter((provider) => !library.signInMethods.includes(provider));
  const canStartConversion = library.conversionEntitlement.canStartConversion;
  const playable = library.audiobooks.filter((conversion) =>
    conversion.state === "FINALIZED"
    && conversion.privateAudiobook?.availability === "AVAILABLE"
    && !deletingAudiobooks[conversion.privateAudiobook.audiobookId]
  );
  const unfinished = library.audiobooks.filter((conversion) => conversion.state !== "FINALIZED");
  const unavailable = library.audiobooks.filter((conversion) =>
    conversion.state === "FINALIZED"
    && (conversion.privateAudiobook?.availability !== "AVAILABLE"
      || Boolean(conversion.privateAudiobook && deletingAudiobooks[conversion.privateAudiobook.audiobookId]))
  );
  const privateAudiobooks = library.audiobooks.filter((conversion) => conversion.privateAudiobook);

  const requestAudiobookDeletion = async (conversion: AudiobookConversion) => {
    const audiobook = conversion.privateAudiobook;
    if (!audiobook || !window.confirm("Delete this Private Audiobook and all of its private content?")) return;
    setDeletionError(undefined);
    try {
      const receipt = await deletePrivateAudiobook(audiobook.audiobookId, audiobook.version, csrf);
      setDeletingAudiobooks((current) => ({ ...current, [audiobook.audiobookId]: receipt }));
    } catch {
      setDeletionError("The deletion request could not be accepted. Refresh the Library and try again.");
    }
  };

  const requestAccountDeletion = async () => {
    if (!window.confirm("Delete your account, every Private Audiobook, and all private content?")) return;
    setDeletionError(undefined);
    try {
      if (browserSupportsManagedOfflineCopies()) {
        await createOfflinePlaybackManager().purgeAll().catch(() => undefined);
      }
      setAccountDeletion(await deleteListenerAccount(csrf));
    } catch {
      setDeletionError("The account deletion request could not be accepted. Please try again.");
    }
  };
  return (
    <section className="library-studio" aria-labelledby="library-title">
      <div className="library-heading">
        <div>
          <div className="eyebrow"><LibraryIcon size={15} /> Private studio</div>
          <h1 id="library-title">Your Library</h1>
          <p>Welcome back, <strong>{library.displayName}</strong>.</p>
        </div>
        <Button disabled={!canStartConversion} onClick={() => setCreationTarget(null)}><Upload size={17} /> Create audiobook</Button>
      </div>

      <div className="library-grid">
        {library.audiobooks.length === 0 ? (
          <article className="empty-library">
            <span className="empty-mark" aria-hidden="true"><BookOpen size={28} /></span>
            <h2>Your first audiobook will live here</h2>
            <p>Choose an authorized PDF or DRM-free EPUB when you’re ready. Nothing leaves your device until you confirm.</p>
            <Button disabled={!canStartConversion} onClick={() => setCreationTarget(null)}><Plus size={17} /> Start a private audiobook</Button>
          </article>
        ) : (
          <div className="conversion-list" aria-label="Private audiobooks">
            {playable.length > 0 && <PrivateAudiobookPlayer audiobooks={playable} csrf={csrf} />}
            {unfinished.map((conversion) => (
              <ConversionCard
                conversion={conversion}
                csrf={csrf}
                key={conversion.conversionId}
                onChooseNarrator={() => setCreationTarget(conversion)}
              />
            ))}
            {unavailable.map((conversion) => (
              <article className="preparing-audiobook" key={conversion.conversionId}>
                <span className="empty-mark" aria-hidden="true"><ShieldCheck size={28} /></span>
                <span className="card-kicker">
                  {conversion.privateAudiobook && deletingAudiobooks[conversion.privateAudiobook.audiobookId]
                    ? "DELETING"
                    : conversion.privateAudiobook?.availability ?? "UNAVAILABLE"}
                </span>
                <h2>Private Audiobook unavailable</h2>
                <p>
                  {conversion.privateAudiobook && deletingAudiobooks[conversion.privateAudiobook.audiobookId]
                    ? `Deletion accepted. Live erasure is due by ${formatDeadline(deletingAudiobooks[conversion.privateAudiobook.audiobookId].liveErasureDueAt)}.`
                    : "Playback is denied while this audiobook is unavailable. No media coordinates are exposed."}
                </p>
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
          <aside className="identity-card privacy-controls" aria-labelledby="privacy-controls-title">
            <span className="card-kicker">Privacy controls</span>
            <h2 id="privacy-controls-title">Delete private content</h2>
            <p>Access is denied as soon as a deletion request is accepted.</p>
            {privateAudiobooks.map((conversion, index) => {
              const audiobook = conversion.privateAudiobook!;
              const receipt = deletingAudiobooks[audiobook.audiobookId];
              return receipt ? (
                <p className="deletion-status" key={audiobook.audiobookId} role="status">
                  Audiobook {index + 1} deletion accepted. Live erasure due {formatDeadline(receipt.liveErasureDueAt)}.
                </p>
              ) : (
                <Button
                  key={audiobook.audiobookId}
                  type="button"
                  variant="outline"
                  disabled={Boolean(accountDeletion)}
                  onClick={() => void requestAudiobookDeletion(conversion)}
                >
                  <Trash2 size={16} /> Delete audiobook {index + 1}
                </Button>
              );
            })}
            <Button
              type="button"
              variant="outline"
              disabled={Boolean(accountDeletion)}
              onClick={() => void requestAccountDeletion()}
            >
              <Trash2 size={16} /> Delete account
            </Button>
            {accountDeletion && (
              <p className="deletion-status" role="status">
                Account deletion accepted. Sign-in is denied and live erasure is due {formatDeadline(accountDeletion.liveErasureDueAt)}.
              </p>
            )}
            {deletionError && <p className="deletion-error" role="alert">{deletionError}</p>}
          </aside>
        </div>
      </div>
      {creationTarget !== undefined && (
        <CreateAudiobookDialog
          csrf={csrf}
          existingConversion={creationTarget ?? undefined}
          onClose={() => setCreationTarget(undefined)}
        />
      )}
    </section>
  );
}

export function LibraryLoading() {
  return <section className="library-studio library-loading" aria-live="polite"><span>Opening your private Library…</span></section>;
}


function providerLabel(provider: string) {
  return provider.charAt(0).toUpperCase() + provider.slice(1);
}

function formatDeadline(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
