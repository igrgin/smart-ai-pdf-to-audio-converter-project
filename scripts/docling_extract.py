#!/usr/bin/env python3
"""Emit bounded, content-preserving Docling evidence for the Java worker."""

import json
import os
import sys
from pathlib import Path

from docling.datamodel.base_models import InputFormat
from docling.datamodel.pipeline_options import PdfPipelineOptions, TesseractCliOcrOptions
from docling.document_converter import DocumentConverter, PdfFormatOption


ROLE_BY_LABEL = {
    "title": "heading",
    "section_header": "heading",
    "table": "table",
    "picture": "figure",
    "formula": "formula",
    "code": "code",
    "footnote": "footnote",
    "page_header": "page_header_footer",
    "page_footer": "page_header_footer",
    "text": "normal_prose",
    "paragraph": "normal_prose",
    "list_item": "normal_prose",
}


def item_text(item, document):
    text = getattr(item, "text", None)
    if text:
        return str(text).strip()
    exporter = getattr(item, "export_to_markdown", None)
    if exporter:
        return str(exporter(document)).strip()
    return ""


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: docling_extract.py SOURCE FIRST_PAGE LAST_PAGE")
    source = Path(sys.argv[1]).resolve(strict=True)
    first_page = int(sys.argv[2])
    last_page = int(sys.argv[3])
    if first_page < 1 or last_page < first_page:
        raise SystemExit("invalid page range")

    artifacts = Path(os.environ.get("DOCLING_ARTIFACTS_PATH", "/opt/docling-models")).resolve(strict=True)
    options = PdfPipelineOptions(
        artifacts_path=artifacts,
        do_ocr=True,
        ocr_options=TesseractCliOcrOptions(lang=["eng"]),
        do_table_structure=True,
        enable_remote_services=False,
    )
    converter = DocumentConverter(
        allowed_formats=[InputFormat.PDF],
        format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=options)},
    )
    document = converter.convert(source, page_range=(first_page, last_page)).document
    evidence = {page: [] for page in range(first_page, last_page + 1)}
    for item, _level in document.iterate_items(traverse_pictures=True):
        label = str(getattr(item, "label", "")).split(".")[-1].lower()
        role = ROLE_BY_LABEL.get(label)
        provenance = getattr(item, "prov", None) or []
        if role is None or not provenance:
            continue
        text = item_text(item, document)
        if not text and role not in {"figure", "formula"}:
            continue
        for page_number in sorted({int(value.page_no) for value in provenance}):
            if first_page <= page_number <= last_page:
                evidence[page_number].append(
                    {"role": role, "text": text, "confidence": 0.9}
                )

    json.dump(
        [
            {"pageNumber": page_number, "items": evidence[page_number]}
            for page_number in range(first_page, last_page + 1)
        ],
        sys.stdout,
        ensure_ascii=False,
        separators=(",", ":"),
    )


if __name__ == "__main__":
    main()
