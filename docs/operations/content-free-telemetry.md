# Content-free telemetry policy

Baseline operational telemetry may describe software and infrastructure, never a Listener or a
publication. This applies equally to local diagnostics and deployed logs, metrics, traces, analytics,
queue metadata, alerts, and dashboards.

Allowed examples:

- application version and immutable revision;
- component or worker stage name;
- bounded status (`AVAILABLE`, `DEGRADED`, success/failure class);
- aggregate duration, count, saturation, and retry metrics with fixed-cardinality labels.

Forbidden examples:

- publication text, titles, filenames, metadata, prompts, model responses, or audio-derived values;
- names, email addresses, provider subjects, session values, IP addresses, or stable Listener IDs;
- object keys, signed URLs, database URLs, credentials, raw exceptions, or message payloads;
- user-controlled values as metric labels or span/log attributes.

The walking skeleton endpoint intentionally exposes only `apiVersion`, build version/revision, and
component availability. Database failures collapse to `DEGRADED`; exception details are not returned
or logged. New telemetry must be reviewed against this allowlist before merge.
