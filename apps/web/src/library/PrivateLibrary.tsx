import { BookOpen, Headphones, Library as LibraryIcon, Plus, ShieldCheck, Sparkles, Upload } from "lucide-react";
import { useEffect, useState } from "react";
import { CreateAudiobookDialog } from "../audiobook-creation";
import { NarrationReviewEditor } from "../narration-review";
import { PrivateAudiobookPlayer } from "../playback";
import { CsrfForm, type CsrfProof } from "../session";
import { Button } from "../ui";
import {
  fetchConversionProgress,
  retryNarrationPlan,
  type AudiobookConversion,
  type ConversionProgress,
  type Library
} from "./api";

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

function ConversionCard({
  conversion,
  csrf,
  onChooseNarrator
}: {
  conversion: AudiobookConversion;
  csrf: CsrfProof;
  onChooseNarrator: () => void;
}) {
  const [progress, setProgress] = useState<ConversionProgress>(conversion);
  const [recoveryBusy, setRecoveryBusy] = useState(false);
  const [recoveryError, setRecoveryError] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    let entityTag: string | undefined;
    let currentState = conversion.state;
    let timeout: number | undefined;
    const poll = async () => {
      let delay = currentState === "PREPARING" ? 3_000 : 15_000;
      try {
        const result = await fetchConversionProgress(conversion.conversionId, entityTag, controller.signal);
        entityTag = result.entityTag;
        if (!result.notModified) {
          setProgress({ ...conversion, ...result.progress });
          currentState = result.progress.state;
          delay = currentState === "PREPARING" ? 3_000 : 15_000;
        }
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError") return;
        delay = 10_000;
      }
      if (!controller.signal.aborted) timeout = window.setTimeout(() => void poll(), delay);
    };
    void poll();
    return () => {
      controller.abort();
      if (timeout !== undefined) window.clearTimeout(timeout);
    };
  }, [conversion.conversionId]);

  if (progress.state === "CANCELLED" || progress.state === "FAILED") {
    const cancelled = progress.state === "CANCELLED";
    return (
      <article className="preparing-audiobook" aria-live="polite">
        <span className="empty-mark" aria-hidden="true"><ShieldCheck size={28} /></span>
        <span className="card-kicker">{progress.reasonCode}</span>
        <h2>{cancelled ? "Conversion cancelled" : "Conversion stopped"}</h2>
        <p>{cancelled
          ? "No further work will be claimed. Partial results remain unavailable while cleanup completes."
          : "No partial Private Audiobook is available. Any reusable allowance has been restored."}</p>
      </article>
    );
  }

  if (progress.state === "PREPARING" || progress.state === "PAUSED") {
    const requiresIntervention = progress.reasonCode === "NARRATION_PLAN_REQUIRES_INTERVENTION";
    const sourceTooDamaged = progress.reasonCode === "SOURCE_TOO_DAMAGED";
    const awaitingExtraction = progress.reasonCode === "EXTRACTION_PENDING";
    return (
      <article className="preparing-audiobook" aria-live="polite">
        <span className="empty-mark" aria-hidden="true"><Sparkles size={28} /></span>
        <span className="card-kicker">{progress.reasonCode}</span>
        <h2>{sourceTooDamaged
          ? "Source copy needs attention"
          : requiresIntervention
            ? "Narration Plan needs attention"
            : "Preparing your private audiobook"}</h2>
        <p>{sourceTooDamaged
          ? progress.recovery?.listenerGuidance ?? "Recovery guidance is unavailable."
          : requiresIntervention
          ? "Preparation could not finish. No further automatic attempts are scheduled."
          : awaitingExtraction
            ? "The PDF passed quarantine inspection and is waiting for bounded extraction or OCR."
          : conversion.explicitNarrationChoiceRequired
            ? "The current Generation Recipe is no longer eligible. Choose explicitly before speech can begin."
            : `The publication passed quarantine inspection. Folio is preparing its Narration Plan with ${conversion.voiceDisplayName ?? "the selected Narrator Voice"} at ${conversion.pace ? paceLabel(conversion.pace) : "Natural"} pace.`}</p>
        {conversion.explicitNarrationChoiceRequired && (
          <Button type="button" variant="outline" onClick={onChooseNarrator}>
            Choose a new Narrator Voice
          </Button>
        )}
        {sourceTooDamaged && progress.allowedActions.includes("RETRY_NARRATION_PLAN") && (
          <Button
            type="button"
            variant="outline"
            disabled={recoveryBusy}
            onClick={() => {
              setRecoveryBusy(true);
              setRecoveryError(false);
              void retryNarrationPlan(progress.conversionId, progress.version, csrf)
                .then(setProgress)
                .catch(() => setRecoveryError(true))
                .finally(() => setRecoveryBusy(false));
            }}
          >
            {recoveryBusy ? "Retrying extraction…" : "Retry bounded extraction"}
          </Button>
        )}
        {recoveryError && <p role="alert">The retry could not be scheduled. Check the source and try again.</p>}
      </article>
    );
  }

  if (progress.state === "GENERATING") {
    return (
      <article className="preparing-audiobook" aria-live="polite">
        <span className="empty-mark" aria-hidden="true"><Headphones size={28} /></span>
        <span className="card-kicker">{progress.reasonCode}</span>
        <h2>Generating your private audiobook</h2>
        <p>Folio is rendering the reviewed Narration Plan with {progress.voiceDisplayName ?? "your selected Narrator Voice"}.</p>
      </article>
    );
  }

  if (progress.reasonCode === "NARRATION_REVIEW_APPROVED"
      || progress.reasonCode === "NARRATION_RECOMMENDATIONS_ACCEPTED") {
    return (
      <article className="preparing-audiobook review-frozen-card" aria-live="polite">
        <span className="empty-mark" aria-hidden="true"><ShieldCheck size={28} /></span>
        <span className="card-kicker">Frozen decision</span>
        <h2>Narration Review approved</h2>
        <p>
          {progress.reasonCode === "NARRATION_RECOMMENDATIONS_ACCEPTED"
            ? "Recommended treatments are frozen for generation."
            : "Your submitted structure, treatments, and Narration Snippets are frozen for generation."}
        </p>
      </article>
    );
  }

  return (
    <article className="preparing-audiobook narration-plan-card" aria-live="polite">
      <span className="empty-mark" aria-hidden="true"><BookOpen size={28} /></span>
      <span className="card-kicker">{progress.reasonCode}</span>
      <h2>Narration Plan ready</h2>
      <p>Review source-backed structure and uncertain or non-prose treatments. Normal prose is not editable.</p>
      {progress.narrationPlan && (
        <NarrationReviewEditor
          conversionId={progress.conversionId}
          csrf={csrf}
          plan={progress.narrationPlan}
          version={progress.version}
          onFrozen={(action, version) => setProgress({
            ...progress,
            version,
            reasonCode: action === "SKIP_OPTIONAL"
              ? "NARRATION_RECOMMENDATIONS_ACCEPTED"
              : "NARRATION_REVIEW_APPROVED",
            allowedActions: []
          })}
          onReload={async () => {
            const result = await fetchConversionProgress(progress.conversionId, undefined, new AbortController().signal);
            if (!result.notModified) setProgress({ ...conversion, ...result.progress });
          }}
        />
      )}
      {progress.explicitNarrationChoiceRequired && (
        <Button type="button" variant="outline" onClick={onChooseNarrator}>
          Choose a new Narrator Voice
        </Button>
      )}
    </article>
  );
}


function EntitlementCard({ entitlement }: { entitlement: Library["conversionEntitlement"] }) {
  if (entitlement.demonstrationOnly) {
    const ending = entitlement.demonstrationSubscriptionStatus === "CANCEL_AT_PERIOD_END"
      ? " Cancellation is scheduled for the end of the current period."
      : "";
    return (
      <aside className={`entitlement-card entitlement-card--${entitlement.canStartConversion ? "available" : "denied"}`} aria-labelledby="entitlement-title">
        <span className="card-kicker">Demonstration Subscription</span>
        <h2 id="entitlement-title">
          {entitlement.canStartConversion
            ? `${entitlement.availableCharacters.toLocaleString("en-US")} narratable characters available`
            : "No Demonstration Subscription grant is available"}
        </h2>
        <p>
          Stripe test mode only. This is not a production payment, tax, payout, or accounting record.{ending}
        </p>
      </aside>
    );
  }
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

export function LibraryLoading() {
  return <section className="library-studio library-loading" aria-live="polite"><span>Opening your private Library…</span></section>;
}


function providerLabel(provider: string) {
  return provider.charAt(0).toUpperCase() + provider.slice(1);
}

function paceLabel(pace: NonNullable<AudiobookConversion["pace"]>): string {
  return pace.charAt(0) + pace.slice(1).toLowerCase();
}
