package dev.audiobook.platform.narration;

import java.nio.file.Path;
import java.time.Duration;

class PdfExtractionCorpusTest extends PdfExtractionCorpusContract {

    @Override
    protected Toolchain toolchain(Corpus corpus) throws Exception {
        Path docling = executable("docling-corpus", corpus == Corpus.MIXED_OUTLINE ? """
                #!/bin/sh
                first="$3"
                last="$4"
                printf '['
                page="$first"
                while [ "$page" -le "$last" ]; do
                  [ "$page" = "$first" ] || printf ','
                  printf '{"pageNumber":%s,"items":[]}' "$page"
                  page=$((page + 1))
                done
                printf ']'
                """ : """
                #!/bin/sh
                first="$3"
                last="$4"
                printf '['
                page="$first"
                while [ "$page" -le "$last" ]; do
                  [ "$page" = "$first" ] || printf ','
                  case "$page" in
                    1) items='{"role":"heading","text":"CHAPTER ONE","confidence":0.98},{"role":"normal_prose","text":"Fully scanned opening.","confidence":0.94}' ;;
                    2) items='' ;;
                    3) items='{"role":"heading","text":"CHAPTER TWO","confidence":0.97},{"role":"normal_prose","text":"Degraded but recoverable text.","confidence":0.89}' ;;
                    *) items='{"role":"normal_prose","text":"Scanned continuation.","confidence":0.93}' ;;
                  esac
                  printf '{"pageNumber":%s,"items":[%s]}' "$page" "$items"
                  page=$((page + 1))
                done
                printf ']'
                """);
        Path tesseract;
        if (corpus == Corpus.MIXED_OUTLINE) {
            Path ocrCount = scratch.resolve("ocr-count");
            tesseract = executable("tesseract-corpus", """
                #!/bin/sh
                count=0
                [ ! -f '%s' ] || count=$(cat '%s')
                count=$((count + 1))
                printf '%%s' "$count" > '%s'
                if [ "$count" = 1 ]; then
                  printf '%%s' 'A scanned page remains accurately recoverable.'
                else
                  printf '%%s' 'The damaged scan has one smudged charact3r.'
                fi
                """.formatted(ocrCount, ocrCount, ocrCount));
        } else {
            tesseract = executable("tesseract-corpus", "#!/bin/sh\nexit 0\n");
        }
        return new Toolchain(docling, tesseract, Duration.ofSeconds(10));
    }
}
