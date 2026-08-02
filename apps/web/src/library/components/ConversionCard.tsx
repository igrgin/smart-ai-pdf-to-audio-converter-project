import { BookOpen, Headphones, ShieldCheck, Sparkles } from "lucide-react";
import { useEffect, useState } from "react";
import { NarrationReviewEditor } from "../../narration-review";
import type { CsrfProof } from "../../session";
import { Button } from "../../ui";
import {
  fetchConversionProgress,
  retryNarrationPlan,
  type AudiobookConversion,
  type ConversionProgress
} from "../api";

export function ConversionCard({
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
          : requiresIntervention ? "Narration Plan needs attention" : "Preparing your private audiobook"}</h2>
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
          <Button type="button" variant="outline" onClick={onChooseNarrator}>Choose a new Narrator Voice</Button>
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
        <p>{progress.reasonCode === "NARRATION_RECOMMENDATIONS_ACCEPTED"
          ? "Recommended treatments are frozen for generation."
          : "Your submitted structure, treatments, and Narration Snippets are frozen for generation."}</p>
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
            reasonCode: action === "SKIP_OPTIONAL" ? "NARRATION_RECOMMENDATIONS_ACCEPTED" : "NARRATION_REVIEW_APPROVED",
            allowedActions: []
          })}
          onReload={async () => {
            const result = await fetchConversionProgress(progress.conversionId, undefined, new AbortController().signal);
            if (!result.notModified) setProgress({ ...conversion, ...result.progress });
          }}
        />
      )}
      {progress.explicitNarrationChoiceRequired && (
        <Button type="button" variant="outline" onClick={onChooseNarrator}>Choose a new Narrator Voice</Button>
      )}
    </article>
  );
}

function paceLabel(pace: NonNullable<AudiobookConversion["pace"]>): string {
  return pace.charAt(0) + pace.slice(1).toLowerCase();
}
