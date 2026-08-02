import { Clock3, ShieldCheck } from "lucide-react";
import { useState } from "react";
import { Button } from "../ui";
import type { CsrfProof, StaffRole } from "../session";
import { fetchCaseDetails, performPrivilegedAction } from "./api";
import { DelegatedRequestForm } from "./components/DelegatedRequestForm";
import { EmergencyAccessForm } from "./components/EmergencyAccessForm";
import { EmergencyReviewForm } from "./components/EmergencyReviewForm";
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
