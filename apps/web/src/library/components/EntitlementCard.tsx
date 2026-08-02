import type { Library } from "../api";

export function EntitlementCard({ entitlement }: { entitlement: Library["conversionEntitlement"] }) {
  if (entitlement.demonstrationOnly) {
    const ending = entitlement.demonstrationSubscriptionStatus === "CANCEL_AT_PERIOD_END"
      ? " Cancellation is scheduled for the end of the current period."
      : "";
    return (
      <aside className={`entitlement-card entitlement-card--${entitlement.canStartConversion ? "available" : "denied"}`} aria-labelledby="entitlement-title">
        <span className="card-kicker">Demonstration Subscription</span>
        <h2 id="entitlement-title">
          {entitlement.canStartConversion
            ? `${entitlement.availableCharacters.toLocaleString("en-US")} narratable characters available`
            : "No Demonstration Subscription grant is available"}
        </h2>
        <p>Stripe test mode only. This is not a production payment, tax, payout, or accounting record.{ending}</p>
      </aside>
    );
  }
  return (
    <aside className={`entitlement-card entitlement-card--${entitlement.canStartConversion ? "available" : "denied"}`} aria-labelledby="entitlement-title">
      <span className="card-kicker">Conversion Entitlement</span>
      <h2 id="entitlement-title">
        {entitlement.canStartConversion
          ? `${entitlement.availableCharacters.toLocaleString("en-US")} narratable characters available`
          : "No free Conversion Entitlement is available yet"}
      </h2>
      <p>
        {entitlement.canStartConversion
          ? "A bounded allowance and provider-cost ceiling are reserved when you activate Create audiobook."
          : "A conversion can start after an approved free grant is added to your Listener Identity."}
      </p>
    </aside>
  );
}
