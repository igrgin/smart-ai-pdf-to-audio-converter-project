import { useState } from "react";
import { Button } from "../components/ui/button";
import type { CsrfProof } from "../identity-session";
import { approveDelegatedRequest, revokeDelegatedGrant } from "./api";
import { formatTime, humanize } from "./format";
import type { DelegatedAccessGrant, ListenerAccessSummary, PendingAccessRequest } from "./types";

export function SupportAccessActivity({ summary, csrf }: { summary: ListenerAccessSummary; csrf: CsrfProof }) {
  const [grants, setGrants] = useState(summary.grants);
  const [pendingRequests, setPendingRequests] = useState(summary.pendingRequests ?? []);
  const [status, setStatus] = useState<string | null>(null);

  const approve = async (request: PendingAccessRequest) => {
    try {
      const grant = await approveDelegatedRequest(request.requestId, csrf);
      setGrants((current) => [grant, ...current]);
      setPendingRequests((current) => current.filter((item) => item.requestId !== request.requestId));
      setStatus("Delegated access approved and the named staff member was notified by policy.");
    } catch {
      setStatus("The delegated grant was not approved. Check the case, scope, and expiry.");
    }
  };

  const revoke = async (grant: DelegatedAccessGrant) => {
    try {
      const revoked = await revokeDelegatedGrant(grant, csrf);
      setGrants((current) => current.map((item) => item.grantId === revoked.grantId ? revoked : item));
      setStatus("Delegated access revoked immediately.");
    } catch {
      setStatus("The grant changed before it could be revoked. Refresh and try again.");
    }
  };

  return (
    <section className="support-access-activity" aria-labelledby="support-access-title">
      <header>
        <div>
          <span className="card-kicker">Listener controls</span>
          <h2 id="support-access-title">Support access activity</h2>
        </div>
        <p>Named, purpose-bound access and notifications visible to this Listener.</p>
      </header>
      <div className="support-access-grid">
        <div>
          <h3>Pending requests</h3>
          {pendingRequests.length === 0 ? <p>No staff access requests await approval.</p> : (
            <ul>{pendingRequests.map((request) => (
              <li key={request.requestId}>
                <strong>{request.staffDisplayName}</strong>
                <span>{humanize(request.purposeCode)} · {humanize(request.allowedActions.join(", "))}</span>
                <span>{humanize(request.restrictionCode)} · {humanize(request.consequenceCode)}</span>
                <span>Opaque {humanize(request.resourceKind)} {request.opaqueResourceReference}</span>
                <span>Expires {formatTime(request.expiresAt)}</span>
                <Button type="button" onClick={() => void approve(request)}>Approve this request</Button>
              </li>
            ))}</ul>
          )}
        </div>
        <div>
          <h3>Delegated grants</h3>
          {grants.length === 0 ? <p>No delegated access grants.</p> : (
            <ul>{grants.map((grant) => (
              <li key={grant.grantId}>
                <strong>{humanize(grant.purposeCode)}</strong>
                <span>Named staff {grant.staffId}</span>
                <span>{grant.revoked ? "Revoked" : `Expires ${formatTime(grant.expiresAt)}`}</span>
                {!grant.revoked && (
                  <Button variant="outline" type="button" onClick={() => void revoke(grant)}>Revoke access</Button>
                )}
              </li>
            ))}</ul>
          )}
        </div>
        <div>
          <h3>Notifications</h3>
          {summary.notifications.length === 0 ? <p>No support access notifications.</p> : (
            <ul>{summary.notifications.map((notification) => (
              <li key={notification.notificationId}>
                <strong>{humanize(notification.eventType)}</strong>
                <span>{formatTime(notification.createdAt)}</span>
              </li>
            ))}</ul>
          )}
        </div>
      </div>
      {status && <p className="operations-status" role="status">{status}</p>}
    </section>
  );
}
