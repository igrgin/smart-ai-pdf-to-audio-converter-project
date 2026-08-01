CREATE ROLE cloudsqlsuperuser NOLOGIN;
CREATE ROLE folio_narration_worker LOGIN PASSWORD 'narration-integration-test-only';
GRANT cloudsqlsuperuser TO folio_narration_worker;
CREATE ROLE folio_speech_worker LOGIN PASSWORD 'speech-integration-test-only';
GRANT cloudsqlsuperuser TO folio_speech_worker;
CREATE ROLE folio_packaging_worker LOGIN PASSWORD 'packaging-integration-test-only';
GRANT cloudsqlsuperuser TO folio_packaging_worker;
