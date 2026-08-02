export interface CsrfProof {
  headerName: string;
  parameterName: string;
  token: string;
}

export interface ListenerSummary {
  displayName: string;
  contactEmail?: string;
  signInMethods: string[];
}

export interface IdentitySession {
  authenticated: boolean;
  listener?: ListenerSummary;
  staff?: { roles: StaffRole[] };
  csrf: CsrfProof;
}

export type StaffRole =
  | "SUPPORT"
  | "RELIABILITY"
  | "ENTITLEMENT"
  | "VOICE"
  | "INCIDENT_RESPONDER"
  | "SECURITY_REVIEWER";

export class SameOriginResponseError extends Error {
  constructor(readonly status: number, resource: string) {
    super(`${resource} returned ${status}`);
    this.name = "SameOriginResponseError";
  }
}

export async function fetchIdentitySession(signal: AbortSignal): Promise<IdentitySession> {
  const response = await fetch("/api/v1/auth/session", {
    headers: { Accept: "application/json" },
    signal
  });
  if (!response.ok) throw new SameOriginResponseError(response.status, "Identity session");
  return response.json() as Promise<IdentitySession>;
}
