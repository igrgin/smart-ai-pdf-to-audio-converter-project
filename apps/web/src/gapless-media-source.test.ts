import { afterEach, describe, expect, it, vi } from "vitest";
import { prepareGaplessPlayback } from "./gapless-media-source";
import type { PlaybackPart } from "./playback";

class TestSourceBuffer extends EventTarget {
  mode: AppendMode = "segments";
  appended: number[] = [];

  appendBuffer(content: BufferSource) {
    this.appended.push(content instanceof ArrayBuffer ? content.byteLength : content.byteLength);
    queueMicrotask(() => this.dispatchEvent(new Event("updateend")));
  }
}

class TestMediaSource extends EventTarget {
  static isTypeSupported = () => true;
  readyState: ReadyState = "open";
  sourceBuffer = new TestSourceBuffer();
  ended = false;

  addSourceBuffer() {
    return this.sourceBuffer;
  }

  endOfStream() {
    this.ended = true;
  }
}

describe("gapless audiobook media source", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("pre-appends bounded parts in sequence mode behind one media URL", async () => {
    const mediaSource = new TestMediaSource();
    vi.stubGlobal("MediaSource", class {
      static isTypeSupported = TestMediaSource.isTypeSupported;
      constructor() {
        return mediaSource;
      }
    });
    vi.stubGlobal("URL", {
      createObjectURL: vi.fn(() => "blob:private-audiobook"),
      revokeObjectURL: vi.fn()
    });
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce({ ok: true, arrayBuffer: async () => new ArrayBuffer(3) })
      .mockResolvedValueOnce({ ok: true, arrayBuffer: async () => new ArrayBuffer(5) }));
    const failed = vi.fn();
    const ready = vi.fn();

    const dispose = prepareGaplessPlayback([part("/one", 3), part("/two", 5)], ready, failed);
    mediaSource.dispatchEvent(new Event("sourceopen"));
    await vi.waitFor(() => expect(mediaSource.ended).toBe(true));

    expect(ready).toHaveBeenCalledWith("blob:private-audiobook");
    expect(mediaSource.sourceBuffer.mode).toBe("sequence");
    expect(mediaSource.sourceBuffer.appended).toEqual([3, 5]);
    expect(failed).not.toHaveBeenCalled();
    dispose();
  });

  it("falls back when an authorized bounded part cannot be buffered", async () => {
    const mediaSource = new TestMediaSource();
    vi.stubGlobal("MediaSource", class {
      static isTypeSupported = TestMediaSource.isTypeSupported;
      constructor() {
        return mediaSource;
      }
    });
    vi.stubGlobal("URL", {
      createObjectURL: vi.fn(() => "blob:failed-audiobook"),
      revokeObjectURL: vi.fn()
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 404 }));
    const failed = vi.fn();
    const ready = vi.fn();

    const dispose = prepareGaplessPlayback([part("/unavailable", 3)], ready, failed);
    mediaSource.dispatchEvent(new Event("sourceopen"));

    await vi.waitFor(() => expect(failed).toHaveBeenCalledOnce());
    expect(mediaSource.ended).toBe(false);
    dispose();
  });

  it("uses one concatenated blob when raw MP3 Media Source sequencing is unavailable", async () => {
    vi.stubGlobal("MediaSource", class {
      static isTypeSupported = () => false;
    });
    vi.stubGlobal("URL", {
      createObjectURL: vi.fn(() => "blob:concatenated-audiobook"),
      revokeObjectURL: vi.fn()
    });
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce({ ok: true, arrayBuffer: async () => new ArrayBuffer(3) })
      .mockResolvedValueOnce({ ok: true, arrayBuffer: async () => new ArrayBuffer(5) }));
    const ready = vi.fn();

    const dispose = prepareGaplessPlayback(
      [part("/one", 3), part("/two", 5)],
      ready,
      vi.fn()
    );

    await vi.waitFor(() => expect(ready).toHaveBeenCalledWith("blob:concatenated-audiobook"));
    expect(URL.createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
    dispose();
  });
});

function part(mediaUrl: string, byteLength: number): PlaybackPart {
  return {
    partId: crypto.randomUUID(),
    ordinal: 0,
    byteLength,
    durationMs: 1_000,
    mimeType: "audio/mpeg",
    entityTag: `sha256:${"a".repeat(64)}`,
    mediaUrl
  };
}
