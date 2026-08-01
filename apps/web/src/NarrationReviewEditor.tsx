import { useEffect, useRef, useState } from "react";
import { Button } from "./components/ui/button";
import type {
  ConversionProgress,
  CsrfProof,
  NarrationReviewItem,
  SourceProvenance
} from "./identity-session";
import {
  NarrationReviewProblem,
  narrationReviewRequestFingerprint,
  submitNarrationReview,
  type NarrationReviewAction,
  type NarrationSectionDecision,
  type NarrationTreatment
} from "./narration-review";

interface SourceSectionDetails {
  ordinal: number;
  provenance: SourceProvenance;
  gaps: Array<{ sourceUnit: string; reasonCode: string }>;
}

interface EditableReviewItem extends Omit<NarrationReviewItem, "recommendedTreatment" | "narrationSnippet"> {
  sourceChapterOrdinal: number;
  treatment: NarrationTreatment;
  narrationSnippet: string;
}

interface EditableSection extends NarrationSectionDecision {
  sourceDetails: SourceSectionDetails[];
  reviewItems: EditableReviewItem[];
}

export function NarrationReviewEditor({
  conversionId,
  version,
  plan,
  csrf,
  onFrozen,
  onReload
}: {
  conversionId: string;
  version: number;
  plan: NonNullable<ConversionProgress["narrationPlan"]>;
  csrf: CsrfProof;
  onFrozen: (action: NarrationReviewAction, version: number) => void;
  onReload: () => Promise<void>;
}) {
  const initialSections = () => plan.chapters.map((chapter): EditableSection => ({
    clientId: `section-${chapter.ordinal}`,
    title: chapter.title ?? `Unavailable source section ${chapter.ordinal + 1}`,
    excluded: false,
    sourceChapterOrdinals: [chapter.ordinal],
    sourceDetails: [{ ordinal: chapter.ordinal, provenance: chapter.provenance, gaps: chapter.gaps }],
    reviewItems: chapter.reviewItems.map((item) => ({
      ...item,
      sourceChapterOrdinal: chapter.ordinal,
      treatment: item.recommendedTreatment,
      narrationSnippet: item.narrationSnippet ?? ""
    }))
  }));
  const [sections, setSections] = useState<EditableSection[]>(initialSections);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<NarrationReviewProblem | null>(null);
  const errorRef = useRef<HTMLDivElement>(null);
  const attemptRef = useRef<{ fingerprint: string; operationKey: string } | null>(null);

  useEffect(() => {
    setSections(initialSections());
    attemptRef.current = null;
  }, [plan, version]);

  const mutateSections = (mutation: (current: EditableSection[]) => EditableSection[]) => {
    attemptRef.current = null;
    setSections(mutation);
  };
  const updateSection = (index: number, update: (section: EditableSection) => EditableSection) => {
    mutateSections((current) => current.map((section, candidate) => candidate === index ? update(section) : section));
  };
  const move = (index: number, offset: number) => {
    mutateSections((current) => {
      const next = [...current];
      const [section] = next.splice(index, 1);
      next.splice(index + offset, 0, section);
      return next;
    });
  };
  const mergeNext = (index: number) => {
    mutateSections((current) => {
      const first = current[index];
      const second = current[index + 1];
      if (!first || !second) return current;
      const merged: EditableSection = {
        ...first,
        sourceChapterOrdinals: [...first.sourceChapterOrdinals, ...second.sourceChapterOrdinals],
        sourceDetails: [...first.sourceDetails, ...second.sourceDetails],
        reviewItems: [...first.reviewItems, ...second.reviewItems],
        excluded: first.excluded && second.excluded
      };
      return [...current.slice(0, index), merged, ...current.slice(index + 2)];
    });
  };
  const split = (index: number) => {
    mutateSections((current) => {
      const section = current[index];
      if (section.sourceChapterOrdinals.length < 2) return current;
      const splitAt = Math.ceil(section.sourceChapterOrdinals.length / 2);
      const firstOrdinals = section.sourceChapterOrdinals.slice(0, splitAt);
      const secondOrdinals = section.sourceChapterOrdinals.slice(splitAt);
      const first = {
        ...section,
        sourceChapterOrdinals: firstOrdinals,
        sourceDetails: section.sourceDetails.filter((source) => firstOrdinals.includes(source.ordinal)),
        reviewItems: section.reviewItems.filter((item) => firstOrdinals.includes(item.sourceChapterOrdinal))
      };
      const second = {
        ...section,
        clientId: `section-${crypto.randomUUID()}`,
        title: `${section.title} (continued)`,
        sourceChapterOrdinals: secondOrdinals,
        sourceDetails: section.sourceDetails.filter((source) => secondOrdinals.includes(source.ordinal)),
        reviewItems: section.reviewItems.filter((item) => secondOrdinals.includes(item.sourceChapterOrdinal))
      };
      return [...current.slice(0, index), first, second, ...current.slice(index + 1)];
    });
  };
  const submit = async (action: NarrationReviewAction) => {
    setBusy(true);
    setError(null);
    const fingerprint = narrationReviewRequestFingerprint(version, action, sections);
    if (attemptRef.current?.fingerprint !== fingerprint) {
      attemptRef.current = {
        fingerprint,
        operationKey: `narration-review:${conversionId}:${crypto.randomUUID()}`
      };
    }
    const operationKey = attemptRef.current.operationKey;
    try {
      const result = await submitNarrationReview(conversionId, version, action, sections, csrf, operationKey);
      attemptRef.current = null;
      onFrozen(result.action, result.conversionVersion);
    } catch (problem) {
      if (problem instanceof NarrationReviewProblem) attemptRef.current = null;
      setError(problem instanceof NarrationReviewProblem
        ? problem
        : new NarrationReviewProblem("NARRATION_REVIEW_FAILED", "The Narration Review could not be saved. Try again.", true));
    } finally {
      setBusy(false);
    }
  };
  const reload = async () => {
    setBusy(true);
    attemptRef.current = null;
    try {
      await onReload();
      setError(null);
    } catch {
      setError(new NarrationReviewProblem(
        "NARRATION_REVIEW_RELOAD_FAILED",
        "The latest Narration Review could not be loaded. Try again.",
        true
      ));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="narration-review-editor">
      <div className="review-guidance" role="note">
        <strong>Bounded review</strong>
        <span>Rename and arrange sections, or adjust only each Review Item’s treatment and Narration Snippet. Normal prose is never editable here.</span>
      </div>
      {error && (
        <div
          className="review-error"
          role="alert"
          tabIndex={-1}
          ref={(node) => {
            errorRef.current = node;
            node?.focus();
          }}
        >
          <strong>Review not saved</strong>
          <span>{error.message}</span>
          {error.recoverable && (
            <Button type="button" variant="outline" disabled={busy} onClick={() => void reload()}>Reload latest review</Button>
          )}
        </div>
      )}
      <section className="narration-review-items" aria-labelledby="review-items-title">
        <h3 id="review-items-title">Review Items</h3>
        {sections.flatMap((section, sectionIndex) => section.reviewItems.map((item, itemIndex) => (
          <fieldset className="narration-review-item" key={`${item.sourceChapterOrdinal}-${item.ordinal}`}>
            <legend>{label(item.type)} · {label(item.treatment)}</legend>
            <span>
              {section.title} · source position {item.sourceOrdinal + 1} · extraction {percent(item.extractionConfidence)}
              {` · classification ${percent(item.classificationConfidence)}`}
              {` · treatment ${percent(item.treatmentConfidence)}`}
            </span>
            <small>
              {item.reasonCode} · Source: {label(item.provenance.source)}
              {` · spine ${item.provenance.spineIndex + 1} · ${item.provenance.spineItem}`}
              {item.provenance.anchor ? ` · anchor ${item.provenance.anchor}` : ""}
            </small>
            <label>
              Treatment for {label(item.type)} in {section.title}
              <select
                value={item.treatment}
                onChange={(event) => updateSection(sectionIndex, (current) => ({
                  ...current,
                  reviewItems: current.reviewItems.map((candidate, candidateIndex) => candidateIndex === itemIndex
                    ? { ...candidate, treatment: event.target.value as NarrationTreatment }
                    : candidate)
                }))}
              >
                <option value="OMIT">Omit</option>
                <option value="READ_VERBATIM">Read verbatim</option>
                <option value="SUMMARIZE">Summarize</option>
                <option value="DESCRIBE">Describe</option>
              </select>
            </label>
            <label>
              Narration Snippet for {label(item.type)} in {section.title}
              <textarea
                value={item.narrationSnippet}
                maxLength={4000}
                onChange={(event) => updateSection(sectionIndex, (current) => ({
                  ...current,
                  reviewItems: current.reviewItems.map((candidate, candidateIndex) => candidateIndex === itemIndex
                    ? { ...candidate, narrationSnippet: event.target.value }
                    : candidate)
                }))}
              />
            </label>
          </fieldset>
        )))}
      </section>
      <section className="narration-structure" aria-labelledby="narration-structure-title">
        <h3 id="narration-structure-title">Narration structure</h3>
        <div className="narration-chapters">
          {sections.map((section, sectionIndex) => (
            <section className={`narration-chapter${section.excluded ? " narration-chapter--excluded" : ""}`} key={section.clientId}>
              <h4>{section.title}</h4>
              <div className="section-title-field">
                <label htmlFor={`${section.clientId}-title`}>Section {sectionIndex + 1} title</label>
                <input
                  id={`${section.clientId}-title`}
                  value={section.title}
                  maxLength={300}
                  onChange={(event) => updateSection(sectionIndex, (current) => ({ ...current, title: event.target.value }))}
                />
              </div>
              <div className="section-tools" aria-label={`Arrange ${section.title}`}>
                <Button type="button" variant="outline" disabled={sectionIndex === 0 || busy} onClick={() => move(sectionIndex, -1)} aria-label={`Move ${section.title} up`}>Move up</Button>
                <Button type="button" variant="outline" disabled={sectionIndex === sections.length - 1 || busy} onClick={() => move(sectionIndex, 1)} aria-label={`Move ${section.title} down`}>Move down</Button>
                <Button type="button" variant="outline" disabled={sectionIndex === sections.length - 1 || busy} onClick={() => mergeNext(sectionIndex)} aria-label={`Merge ${section.title} with next section`}>Merge next</Button>
                <Button type="button" variant="outline" disabled={busy || sections.length >= 400 || section.sourceChapterOrdinals.length < 2} onClick={() => split(sectionIndex)} aria-label={`Split ${section.title} section`}>Split</Button>
              </div>
              <label className="exclude-section">
                <input
                  type="checkbox"
                  checked={section.excluded}
                  onChange={(event) => updateSection(sectionIndex, (current) => ({ ...current, excluded: event.target.checked }))}
                />
                Exclude {section.title} from narration
              </label>
              {section.sourceDetails.map((source) => (
                <div className="section-source" key={source.ordinal}>
                  <p className="narration-provenance">
                    Source: {label(source.provenance.source)} · spine {source.provenance.spineIndex + 1} · section {source.ordinal + 1}
                    {source.provenance.anchor ? ` · anchor ${source.provenance.anchor}` : ""}
                    {` · confidence ${percent(source.provenance.confidence)}`}
                  </p>
                  {source.gaps.map((gap) => (
                    <p className="narration-gap" key={`${gap.sourceUnit}-${gap.reasonCode}`}>
                      Explicit gap · {gap.reasonCode}
                    </p>
                  ))}
                </div>
              ))}
            </section>
          ))}
        </div>
      </section>
      <div className="review-submit-actions">
        <Button type="button" variant="outline" disabled={busy} onClick={() => void submit("SKIP_OPTIONAL")}>Skip optional review</Button>
        <Button type="button" disabled={busy || sections.some((section) => !section.title.trim())} onClick={() => void submit("APPROVE")}>Approve Narration Review</Button>
      </div>
      <p className="review-status" aria-live="polite">{busy ? "Freezing review decisions…" : "Review decisions are not frozen until you approve or skip."}</p>
    </div>
  );
}

function label(value: string): string {
  return value.toLowerCase().replaceAll("_", " ").replace(/^./, (character) => character.toUpperCase());
}

function percent(value: number): string {
  return `${Math.round(value * 100)}%`;
}
