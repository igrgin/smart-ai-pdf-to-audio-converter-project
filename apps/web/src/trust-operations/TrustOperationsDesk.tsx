import { Clock3, ShieldCheck } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Button } from "../components/ui/button";
import type { CsrfProof, StaffRole } from "../identity-session";
import { fetchCaseDetails, mutationHeaders, performPrivilegedAction } from "./api";
import { formatTime, humanize } from "./format";
import type { ActionQueue, CaseDetails, PrivilegedActionResult } from "./types";

export function TrustOperationsDesk({
  queue,
  csrf,
  staffRoles
}: {
  queue: ActionQueue;
  csrf: CsrfProof;
  staffRoles: StaffRole[];
}) {
  const [selectedCase, setSelectedCase] = useState<CaseDetails | null>(null);
  const [authorizedResource, setAuthorizedResource] = useState<PrivilegedActionResult["authorizedResource"] | null>(null);
  const [status, setStatus] = useState<string | null>(null);

  const reviewCase = async (caseId: string) => {
    setStatus(null);
    try {
      setSelectedCase(await fetchCaseDetails(caseId));
    } catch {
      setStatus("Case details are no longer available for this role.");
    }
  };

  const useApprovedAccess = async () => {
    if (!selectedCase) return;
    try {
      const result = await performPrivilegedAction(selectedCase.caseId, csrf);
      setAuthorizedResource(result.authorizedResource);
      setStatus("The approved resource reference was disclosed and audited.");
    } catch {
      setAuthorizedResource(null);
      setStatus("No active delegated or emergency grant permits that action.");
    }
  };

  return (
    <section className="operations-desk" aria-labelledby="action-queue-title">
      <header className="operations-heading">
        <div>
          <div className="eyebrow"><ShieldCheck size={15} /> Named staff workspace</div>
          <h1 id="action-queue-title">Action Queue</h1>
        </div>
        <p>Ordered by safety, deadline, and urgency. Context remains opaque until an active grant permits more.</p>
      </header>
      <div className="action-queue" aria-label="Role-scoped Action Queue">
        {queue.cases.length === 0 ? (
          <p className="operations-empty">No cases currently require action for your roles.</p>
        ) : queue.cases.map((operationsCase) => (
          <article className="action-case" key={operationsCase.caseId}>
            <div className="action-case-heading">
              <span className="card-kicker">{operationsCase.restrictionCode.replaceAll("_", " ")}</span>
              <span className="case-type">{humanize(operationsCase.type)}</span>
            </div>
            <h2>{humanize(operationsCase.consequenceCode)}</h2>
            <dl>
              <div><dt>Opaque resource</dt><dd>{operationsCase.opaqueResourceReference}</dd></div>
              <div><dt>Deadline</dt><dd><Clock3 size={14} /> {formatTime(operationsCase.deadline)}</dd></div>
              <div><dt>Priority</dt><dd>Safety {operationsCase.safetyPriority} · Urgency {operationsCase.urgency}</dd></div>
            </dl>
            <Button variant="outline" type="button" onClick={() => void reviewCase(operationsCase.caseId)}>
              Review bounded case
            </Button>
          </article>
        ))}
      </div>
      {selectedCase && (
        <section className="case-workbench" aria-labelledby="case-workbench-title">
          <div>
            <span className="card-kicker">Opaque case context</span>
            <h2 id="case-workbench-title">{humanize(selectedCase.consequenceCode)}</h2>
            <p>{humanize(selectedCase.restrictionCode)} · deadline {formatTime(selectedCase.deadline)}</p>
          </div>
          <Button type="button" onClick={() => void useApprovedAccess()}>Use approved access</Button>
          {authorizedResource && (
            <p className="authorized-resource"><strong>{humanize(authorizedResource.kind)}</strong> {authorizedResource.id}</p>
          )}
          <div className="case-audit">
            <h3>Content-free audit</h3>
            {selectedCase.auditEvents.length === 0 ? <p>No privileged actions recorded.</p> : (
              <ul>{selectedCase.auditEvents.map((event) => (
                <li key={event.eventId}>
                  <strong>{humanize(event.action)} · {humanize(event.outcome)}</strong>
                  <span>{humanize(event.authority)} · {formatTime(event.occurredAt)}</span>
                </li>
              ))}</ul>
            )}
          </div>
          {!staffRoles.includes("INCIDENT_RESPONDER") && <DelegatedRequestForm caseId={selectedCase.caseId} csrf={csrf} />}
          {staffRoles.includes("INCIDENT_RESPONDER") && <EmergencyAccessForm caseId={selectedCase.caseId} csrf={csrf} />}
        </section>
      )}
      {staffRoles.includes("SECURITY_REVIEWER") && <EmergencyReviewForm csrf={csrf} />}
      {status && <p className="operations-status" role="status">{status}</p>}
    </section>
  );
}

function DelegatedRequestForm({ caseId, csrf }: { caseId: string; csrf: CsrfProof }) {
  const [status, setStatus] = useState<string | null>(null);
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const response = await fetch(`/api/v1/operator/action-queue/${caseId}/delegated-access-requests`, {
      method: "POST",
      headers: mutationHeaders(csrf, { "Idempotency-Key": crypto.randomUUID() }),
      body: JSON.stringify({
        purposeCode: data.get("purposeCode"),
        allowedActions: ["VIEW_RESOURCE_REFERENCE"],
        expiresAt: new Date(String(data.get("expiresAt"))).toISOString()
      })
    });
    setStatus(response.ok ? "The Listener can now review your named, bounded access request."
      : "The access request was not accepted. Check its purpose and expiry.");
  };
  return (
    <form className="support-access-form" onSubmit={(event) => void submit(event)}>
      <h3>Request Listener approval</h3>
      <label>Purpose<input name="purposeCode" required placeholder="RESTORE_PLAYBACK" /></label>
      <label>Hard expiry<input name="expiresAt" type="datetime-local" required /></label>
      <Button type="submit">Request minimum access</Button>
      {status && <p role="status">{status}</p>}
    </form>
  );
}

function EmergencyAccessForm({ caseId, csrf }: { caseId: string; csrf: CsrfProof }) {
  const [status, setStatus] = useState<string | null>(null);
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const response = await fetch(`/api/v1/operator/action-queue/${caseId}/emergency-access`, {
      method: "POST",
      headers: mutationHeaders(csrf, { "Idempotency-Key": crypto.randomUUID() }),
      body: JSON.stringify({
        incidentReference: data.get("incidentReference"),
        justificationCode: data.get("justificationCode"),
        purposeCode: data.get("purposeCode"),
        allowedActions: ["VIEW_RESOURCE_REFERENCE"],
        expiresAt: new Date(String(data.get("expiresAt"))).toISOString()
      })
    });
    setStatus(response.ok ? "Emergency access started; Listener notification and independent review are required."
      : "Emergency access denied. Fresh MFA and incident-bound minimum scope are required.");
  };
  return (
    <form className="emergency-access-form" onSubmit={(event) => void submit(event)}>
      <h3>Start emergency access</h3>
      <label>Incident reference<input name="incidentReference" required /></label>
      <label>Justification code<input name="justificationCode" required /></label>
      <label>Purpose<input name="purposeCode" required /></label>
      <label>Hard expiry<input name="expiresAt" type="datetime-local" required /></label>
      <Button type="submit">Start incident-bound access</Button>
      {status && <p role="status">{status}</p>}
    </form>
  );
}

function EmergencyReviewForm({ csrf }: { csrf: CsrfProof }) {
  const [status, setStatus] = useState<string | null>(null);
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const grantId = String(data.get("grantId"));
    const response = await fetch(`/api/v1/operator/action-queue/emergency-access-grants/${grantId}/review`, {
      method: "POST",
      headers: mutationHeaders(csrf, { "Idempotency-Key": crypto.randomUUID() }),
      body: JSON.stringify({ outcome: data.get("outcome"), reviewCode: data.get("reviewCode") })
    });
    setStatus(response.ok ? "Independent emergency-access review recorded immutably." : "Review could not be recorded.");
  };
  return (
    <form className="emergency-review-form" onSubmit={(event) => void submit(event)}>
      <h2>Independent emergency review</h2>
      <label>Grant ID<input name="grantId" required /></label>
      <label>Outcome<select name="outcome"><option>APPROPRIATE</option><option>POLICY_GAP</option><option>UNJUSTIFIED</option></select></label>
      <label>Review code<input name="reviewCode" required /></label>
      <Button type="submit">Record retrospective review</Button>
      {status && <p role="status">{status}</p>}
    </form>
  );
}
