#!/usr/bin/env bash
set -euo pipefail

readonly PREVIEW_API_BASE="${PREVIEW_API_BASE:-https://eu.api.openai.com/v1}"
readonly PREVIEW_MODEL="${PREVIEW_MODEL:-gpt-4o-mini-tts-2025-12-15}"
readonly PREVIEW_FFMPEG_BIN="${PREVIEW_FFMPEG_BIN:-ffmpeg}"
readonly PREVIEW_OUTPUT_DIR="${PREVIEW_OUTPUT_DIR:-apps/web/public/samples/narrator-voices}"
readonly PREVIEW_PASSAGE="At midnight, the little library kept one lamp burning. Its warm circle of light rested on a book no one remembered shelving. When Mara opened the cover, the first page whispered: every beginning is small enough to hold in one hand. She carried the book to the window, where rain made silver paths across the glass. With each page, the quiet room seemed to breathe around her."

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "OPENAI_API_KEY is required to render mapping-faithful Narrator Voice previews" >&2
  exit 1
fi

preview_tmp="$(mktemp -d)"
trap 'rm -rf "${preview_tmp}"' EXIT
mkdir -p "${PREVIEW_OUTPUT_DIR}"

render_preview() {
  local slug="$1"
  local provider_voice="$2"
  local instructions="$3"
  local source_file="${preview_tmp}/${slug}.mp3"
  local output_file="${PREVIEW_OUTPUT_DIR}/${slug}-folio-preview-v1.mp3"

  jq -n \
    --arg model "${PREVIEW_MODEL}" \
    --arg input "${PREVIEW_PASSAGE}" \
    --arg voice "${provider_voice}" \
    --arg instructions "${instructions}" \
    '{model:$model,input:$input,voice:$voice,instructions:$instructions,response_format:"mp3",speed:1.0}' \
    | curl --fail-with-body --silent --show-error \
        -H "Authorization: Bearer ${OPENAI_API_KEY}" \
        -H "Content-Type: application/json" \
        --data-binary @- \
        "${PREVIEW_API_BASE}/audio/speech" \
        --output "${source_file}"
  "${PREVIEW_FFMPEG_BIN}" -hide_banner -loglevel error -y -i "${source_file}" \
    -af apad -t 29 -ac 1 -ar 24000 -codec:a libmp3lame -b:a 64k -map_metadata -1 "${output_file}"
}

render_preview rowan cedar "Voice: Warm and grounded audiobook narration. Delivery: Natural pace, attentive phrasing, and calm confidence."
render_preview marlowe marin "Voice: Clear and assured audiobook narration. Delivery: Natural pace, precise phrasing, and composed confidence."
render_preview ellis coral "Voice: Bright and expressive audiobook narration. Delivery: Natural pace, lively phrasing, and emotionally attentive restraint."
render_preview clara ballad "Voice: Calm and intimate audiobook narration. Delivery: Natural pace, gentle phrasing, and close, reassuring presence."
render_preview ansel ash "Voice: Open and conversational audiobook narration. Delivery: Natural pace, easy phrasing, and an inviting, unforced presence."
render_preview sloane sage "Voice: Poised and reflective audiobook narration. Delivery: Natural pace, thoughtful phrasing, and quiet emotional depth."
