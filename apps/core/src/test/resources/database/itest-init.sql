CREATE ROLE cloudsqlsuperuser NOLOGIN;
CREATE ROLE folio_narration_worker LOGIN PASSWORD 'narration-integration-test-only';
GRANT cloudsqlsuperuser TO folio_narration_worker;
