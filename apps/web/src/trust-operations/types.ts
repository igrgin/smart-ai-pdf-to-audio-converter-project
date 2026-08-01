export interface OperationsCase {
  caseId: string;
  type: "SUPPORT" | "EXPIRING_ACCESS" | "FAILED_STAGE" | "ENTITLEMENT_INTERVENTION" | "VOICE_AVAILABILITY" | "SERVICE_INCIDENT";
  opaqueResourceReference: string;
  resourceKind: string;
  restrictionCode: string;
  consequenceCode: string;
  deadline: string;
  safetyPriority: number;
  urgency: number;
  allowedActions: string[];
}

export interface ActionQueue {
  cases: OperationsCase[];
}

export interface DelegatedAccessGrant {
  grantId: string;
  caseId: string;
  staffId: string;
  purposeCode: string;
  allowedActions: string[];
  validFrom: string;
  expiresAt: string;
  revoked: boolean;
  version: number;
}

export interface ListenerNotification {
  notificationId: string;
  caseId: string;
  grantId?: string;
  eventType: string;
  createdAt: string;
}

export interface PendingAccessRequest {
  requestId: string;
  caseId: string;
  staffId: string;
  staffDisplayName: string;
  opaqueResourceReference: string;
  resourceKind: string;
  restrictionCode: string;
  consequenceCode: string;
  deadline: string;
  purposeCode: string;
  allowedActions: string[];
  expiresAt: string;
  requestedAt: string;
}

export interface ListenerAccessSummary {
  grants: DelegatedAccessGrant[];
  notifications: ListenerNotification[];
  pendingRequests?: PendingAccessRequest[];
}

export interface AuditEvent {
  eventId: string;
  authority: string;
  purposeCode: string;
  policyCode: string;
  action: string;
  outcome: string;
  occurredAt: string;
  reviewObligation?: string;
  appealObligation?: string;
}

export interface CaseDetails extends OperationsCase {
  auditEvents: AuditEvent[];
}

export interface PrivilegedActionResult {
  authorizedResource: { kind: string; id: string };
  auditEvent: AuditEvent;
}
