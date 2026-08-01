import type { CsrfProof } from "../identity-session";
import type {
  ActionQueue,
  CaseDetails,
  DelegatedAccessGrant,
  ListenerAccessSummary,
  PrivilegedActionResult
} from "./types";

export async function fetchActionQueue(signal: AbortSignal): Promise<ActionQueue> {
  const response = await fetch("/api/v1/operator/action-queue", {
    headers: { Accept: "application/json" },
    signal
  });
  if (!response.ok) throw new Error(`Action Queue returned ${response.status}`);
  return response.json() as Promise<ActionQueue>;
}

export async function fetchListenerAccess(signal: AbortSignal): Promise<ListenerAccessSummary> {
  const response = await fetch("/api/v1/support-access-grants", {
    headers: { Accept: "application/json" },
    signal
  });
  if (!response.ok) throw new Error(`Support access activity returned ${response.status}`);
  return response.json() as Promise<ListenerAccessSummary>;
}

export async function fetchCaseDetails(caseId: string): Promise<CaseDetails> {
  const response = await fetch(`/api/v1/operator/action-queue/${caseId}`, {
    headers: { Accept: "application/json" }
  });
  if (!response.ok) throw new Error(`Case details returned ${response.status}`);
  return response.json() as Promise<CaseDetails>;
}

export async function performPrivilegedAction(
  caseId: string,
  csrf: CsrfProof
): Promise<PrivilegedActionResult> {
  const response = await fetch(`/api/v1/operator/action-queue/${caseId}/actions`, {
    method: "POST",
    headers: mutationHeaders(csrf, { "Idempotency-Key": crypto.randomUUID() }),
    body: JSON.stringify({ action: "VIEW_RESOURCE_REFERENCE" })
  });
  if (!response.ok) throw new Error(`Privileged action returned ${response.status}`);
  return response.json() as Promise<PrivilegedActionResult>;
}

export async function approveDelegatedRequest(
  requestId: string,
  csrf: CsrfProof
): Promise<DelegatedAccessGrant> {
  const response = await fetch("/api/v1/support-access-grants", {
    method: "POST",
    headers: mutationHeaders(csrf, { "Idempotency-Key": crypto.randomUUID() }),
    body: JSON.stringify({ requestId })
  });
  if (!response.ok) throw new Error(`Delegated approval returned ${response.status}`);
  return response.json() as Promise<DelegatedAccessGrant>;
}

export async function revokeDelegatedGrant(
  grant: DelegatedAccessGrant,
  csrf: CsrfProof
): Promise<DelegatedAccessGrant> {
  const response = await fetch(`/api/v1/support-access-grants/${grant.grantId}/revocation`, {
    method: "POST",
    headers: mutationHeaders(csrf, {
      "Idempotency-Key": crypto.randomUUID(),
      "If-Match": `"${grant.version}"`
    })
  });
  if (!response.ok) throw new Error(`Delegated revocation returned ${response.status}`);
  return response.json() as Promise<DelegatedAccessGrant>;
}

export function mutationHeaders(csrf: CsrfProof, extra: Record<string, string>): Record<string, string> {
  return { Accept: "application/json", "Content-Type": "application/json", [csrf.headerName]: csrf.token, ...extra };
}
