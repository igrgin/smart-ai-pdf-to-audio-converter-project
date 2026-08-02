import type { PlaybackPart } from "./api";

export function prepareGaplessPlayback(
  parts: PlaybackPart[],
  onReady: (url: string) => void,
  onFailure: () => void
): () => void {
  const abortController = new AbortController();
  const objectUrls = new Set<string>();
  let disposed = false;

  const prepareBlobFallback = async () => {
    try {
      const content = await Promise.all(parts.map((part) => readPart(part, abortController.signal)));
      if (disposed) return;
      const url = URL.createObjectURL(new Blob(content, { type: "audio/mpeg" }));
      objectUrls.add(url);
      onReady(url);
    } catch (error) {
      if (!(error instanceof DOMException && error.name === "AbortError")) onFailure();
    }
  };

  if (("MediaSource" in window) && MediaSource.isTypeSupported("audio/mpeg")) {
    const mediaSource = new MediaSource();
    const url = URL.createObjectURL(mediaSource);
    objectUrls.add(url);
    onReady(url);
    mediaSource.addEventListener("sourceopen", () => {
      void appendParts(mediaSource, parts, abortController.signal).catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") return;
        objectUrls.delete(url);
        URL.revokeObjectURL(url);
        void prepareBlobFallback();
      });
    }, { once: true });
  } else {
    void prepareBlobFallback();
  }

  return () => {
    disposed = true;
    abortController.abort();
    objectUrls.forEach((url) => URL.revokeObjectURL(url));
    objectUrls.clear();
  };
}

async function appendParts(
  mediaSource: MediaSource,
  parts: PlaybackPart[],
  signal: AbortSignal
): Promise<void> {
  const sourceBuffer = mediaSource.addSourceBuffer("audio/mpeg");
  sourceBuffer.mode = "sequence";
  for (const part of parts) {
    await append(sourceBuffer, await readPart(part, signal));
  }
  if (mediaSource.readyState === "open") mediaSource.endOfStream();
}

async function readPart(part: PlaybackPart, signal: AbortSignal): Promise<ArrayBuffer> {
  const response = await fetch(part.mediaUrl, {
    headers: { Accept: "audio/mpeg" },
    signal
  });
  if (!response.ok) throw new Error(`Audiobook media returned ${response.status}`);
  return response.arrayBuffer();
}

function append(sourceBuffer: SourceBuffer, content: ArrayBuffer): Promise<void> {
  return new Promise((resolve, reject) => {
    const completed = () => {
      cleanup();
      resolve();
    };
    const failed = () => {
      cleanup();
      reject(new Error("Unable to buffer an audiobook part"));
    };
    const cleanup = () => {
      sourceBuffer.removeEventListener("updateend", completed);
      sourceBuffer.removeEventListener("error", failed);
    };
    sourceBuffer.addEventListener("updateend", completed, { once: true });
    sourceBuffer.addEventListener("error", failed, { once: true });
    sourceBuffer.appendBuffer(content);
  });
}
