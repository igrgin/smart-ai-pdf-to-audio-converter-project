import { BookOpen, Library as LibraryIcon, Plus, ShieldCheck, Upload } from "lucide-react";
import { useState } from "react";
import { CreateAudiobookDialog } from "../audiobook-creation";
import { PrivateAudiobookPlayer } from "../playback";
import { CsrfForm, type CsrfProof } from "../session";
import { Button } from "../ui";
import type { AudiobookConversion, Library } from "./api";
import { ConversionCard } from "./components/ConversionCard";
import { EntitlementCard } from "./components/EntitlementCard";

const providers = ["google", "apple", "facebook"] as const;

export function PrivateLibrary({ library, csrf }: { library: Library; csrf: CsrfProof }) {
  const [methodsOpen, setMethodsOpen] = useState(false);
  const [creationTarget, setCreationTarget] = useState<AudiobookConversion | null | undefined>(undefined);
  const availableProviders = providers.filter((provider) => !library.signInMethods.includes(provider));
  const canStartConversion = library.conversionEntitlement.canStartConversion;
  const playable = library.audiobooks.filter((conversion) =>
    conversion.state === "FINALIZED"
    && conversion.privateAudiobook?.availability === "AVAILABLE"
  );
  const unfinished = library.audiobooks.filter((conversion) => conversion.state !== "FINALIZED");
  const unavailable = library.audiobooks.filter((conversion) =>
    conversion.state === "FINALIZED"
    && conversion.privateAudiobook?.availability !== "AVAILABLE"
  );
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
                <span className="card-kicker">{conversion.privateAudiobook?.availability ?? "UNAVAILABLE"}</span>
                <h2>Private Audiobook unavailable</h2>
                <p>Playback is denied while this audiobook is unavailable. No media coordinates are exposed.</p>
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
