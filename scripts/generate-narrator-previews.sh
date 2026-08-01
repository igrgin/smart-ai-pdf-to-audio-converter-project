#!/usr/bin/env bash
set -euo pipefail

readonly PREVIEW_FFMPEG_BIN="${PREVIEW_FFMPEG_BIN:-ffmpeg}"
readonly PREVIEW_OUTPUT_DIR="${PREVIEW_OUTPUT_DIR:-apps/web/public/samples/narrator-voices}"
readonly PREVIEW_PASSAGE="At midnight, the little library kept one lamp burning. Its warm circle of light rested on a book no one remembered shelving. When Mara opened the cover, the first page whispered: every beginning is small enough to hold in one hand. She carried the book to the window, where rain made silver paths across the glass. With each page, the quiet room seemed to breathe around her."

preview_tmp="$(mktemp -d)"
trap 'rm -rf "${preview_tmp}"' EXIT
mkdir -p "${PREVIEW_OUTPUT_DIR}"

render_preview() {
  local slug="$1"
  local system_voice="$2"
  local speech_rate="$3"
  local source_file="${preview_tmp}/${slug}.aiff"
  local output_file="${PREVIEW_OUTPUT_DIR}/${slug}-folio-preview-v1.mp3"

  say -v "${system_voice}" -r "${speech_rate}" -o "${source_file}" -- "${PREVIEW_PASSAGE}"
  "${PREVIEW_FFMPEG_BIN}" -hide_banner -loglevel error -y -i "${source_file}" \
    -af apad -t 29 -ac 1 -ar 24000 -codec:a libmp3lame -b:a 64k -map_metadata -1 "${output_file}"
}

render_preview rowan Daniel 135
render_preview marlowe Samantha 130
render_preview ellis Moira 135
render_preview clara "Flo (English (UK))" 150
render_preview ansel Karen 130
render_preview sloane "Reed (English (US))" 150
