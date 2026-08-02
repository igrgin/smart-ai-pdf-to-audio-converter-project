import { Pause, Play, Sparkles, Upload } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { CsrfProof } from "../session";
import { Button } from "../ui";
import {
  publicationMediaType,
  submitAuthorizedPublication,
  type Submission,
  type TransferStage
} from "./publication-submission";
import {
  confirmGenerationRecipe,
  fetchVoiceCatalog,
  type ConfirmedGenerationRecipe,
  type NarrationPace,
  type NarratorVoice,
  type VoiceCatalog
} from "./narration-selection";

interface ExistingConversion {
  conversionId: string;
  version: number;
}

export function CreateAudiobookDialog({
  csrf,
  existingConversion,
  onClose
}: {
  csrf: CsrfProof;
  existingConversion?: ExistingConversion;
  onClose: () => void;
}) {
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
    const supported = publicationMediaType(candidate) !== null;
    setFile(supported ? candidate : null);
    if (!supported) setStage("FAILED");
  };

  const create = async () => {
    if ((!existingConversion && (!file || !attested)) || !selectedVoiceId || busy) return;
    setSubmission(null);
    setRecipe(null);
    try {
      const result = existingConversion
        ? { submissionId: "", state: "ADMITTED" as const, conversionId: existingConversion.conversionId }
        : await submitAuthorizedPublication(file!, csrf, setStage);
      if (!existingConversion) setSubmission(result);
      if (result.state !== "ADMITTED" || !result.conversionId) throw new Error("Conversion is not ready for narration");
      setStage("CONFIRMING");
      const confirmed = await confirmGenerationRecipe(
        result.conversionId,
        selectedVoiceId,
        pace,
        csrf,
        existingConversion?.version ?? 0
      );
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
        <span className="card-kicker">{existingConversion ? "Explicit narration re-choice" : "Private creation flow"}</span>
        <h2 id="create-title">{existingConversion ? "Choose a new Narrator Voice" : "Create a private audiobook"}</h2>
        {stage === "PREPARING" ? (
          <div className="creation-result" aria-live="polite">
            <Sparkles size={24} />
            <h3>Preparing your private audiobook</h3>
            <p>
              The publication passed quarantine inspection. {recipe?.voiceDisplayName} at {paceLabel(recipe?.pace ?? pace)} pace
              is frozen for generation while Folio prepares its narration plan.
            </p>
            <span>Conversion {(recipe?.conversionId ?? submission?.conversionId)?.slice(0, 8)}</span>
          </div>
        ) : (
          <>
            {!existingConversion && <label
              className="publication-dropzone"
              onDragOver={(event) => event.preventDefault()}
              onDrop={(event) => {
                event.preventDefault();
                choose(event.dataTransfer.files[0]);
              }}
            >
              <Upload size={24} />
              <strong>Choose PDF or DRM-free EPUB</strong>
              <span>or drop one file here · up to 250 MiB</span>
              <input
                type="file"
                accept=".pdf,.epub,application/pdf,application/epub+zip"
                onChange={(event) => choose(event.target.files?.[0])}
                disabled={busy}
              />
            </label>}
            {!existingConversion && file && <p className="local-file">{file.name} stays on this device until you activate Create audiobook.</p>}
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
            {!existingConversion && <label className="attestation-check">
              <input type="checkbox" checked={attested} onChange={(event) => setAttested(event.target.checked)} disabled={busy} />
              <span>I attest that I’m permitted to reproduce this publication as a private audiobook.</span>
            </label>}
            {!existingConversion && <p className="rights-note">Rights Attestation · terms rights-v1 · notice notice-v1</p>}
            {stage && <p className={`creation-status creation-status--${stage.toLowerCase()}`} aria-live="polite">{stageLabel(stage)}</p>}
            <Button
              type="button"
              onClick={() => void create()}
              disabled={(!existingConversion && (!file || !attested)) || !selectedVoice || busy}
            >
              <Upload size={17} /> {existingConversion ? "Confirm new choice" : "Create audiobook"}
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
  if (stage === "HASHING") return "Checking the publication locally…";
  if (stage === "UPLOADING") return "Transferring to private quarantine…";
  if (stage === "INSPECTING") return "Inspecting the publication…";
  if (stage === "CONFIRMING") return "Freezing your exact Narrator Voice and Narration Pace…";
  return "This publication could not be submitted. Check the file and try again.";
}
