import { useState, type FormEvent } from "react";
import type { CsrfProof } from "../../session";
import { Button } from "../../ui";
import { mutationHeaders } from "../api";

export function EmergencyAccessForm({ caseId, csrf }: { caseId: string; csrf: CsrfProof }) {
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
