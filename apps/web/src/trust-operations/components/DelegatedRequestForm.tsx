import { useState, type FormEvent } from "react";
import type { CsrfProof } from "../../session";
import { Button } from "../../ui";
import { mutationHeaders } from "../api";

export function DelegatedRequestForm({ caseId, csrf }: { caseId: string; csrf: CsrfProof }) {
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
