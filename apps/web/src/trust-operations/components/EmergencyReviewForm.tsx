import { useState, type FormEvent } from "react";
import type { CsrfProof } from "../../session";
import { Button } from "../../ui";
import { mutationHeaders } from "../api";

export function EmergencyReviewForm({ csrf }: { csrf: CsrfProof }) {
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
