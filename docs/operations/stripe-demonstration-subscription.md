# Stripe Demonstration Subscription

Folio's subscription integration is deliberately limited to Stripe sandbox evidence. It does not
offer live payments and does not make production payment, tax, payout, or accounting claims.

## Runtime contract

- Configure a Stripe sandbox webhook destination at
  `POST /api/v1/integrations/stripe/events`.
- Subscribe only to `invoice.paid`, `invoice.voided`, `invoice.marked_uncollectible`,
  `customer.subscription.updated`, `customer.subscription.deleted`, `refund.created`, and
  `refund.updated`.
- Set `STRIPE_WEBHOOK_SECRET` to that destination's signing secret.
- Set `STRIPE_DEMONSTRATION_MONTHLY_PRICE_ID` to the sandbox monthly Price ID and
  `STRIPE_DEMONSTRATION_MONTHLY_GRANT_CHARACTERS` to the local character grant.
- Put the application Listener UUID in the Subscription metadata key `listener_id`. Stripe copies
  that metadata into the invoice's immutable subscription-details snapshot.

The endpoint verifies the raw payload and `Stripe-Signature`, rejects live-mode events, and writes
each sandbox event to an idempotent inbox before projection. Admission reads only PostgreSQL grants;
it never calls Stripe.

## Test Clock verification

Create a sandbox Customer and monthly Subscription under a Stripe Test Clock, using the configured
Price and `listener_id` metadata. Forward the listed events to the local endpoint with the Stripe
CLI, then verify these scenarios:

1. Pause the projector through
   `POST /api/v1/operator/demonstration-subscriptions/projector/pause`, pay an invoice, and confirm
   conversion remains denied while the inbox event is `PENDING`.
2. Resume through
   `POST /api/v1/operator/demonstration-subscriptions/projector/resume` and confirm the local monthly
   grant appears before conversion becomes admissible.
3. Resend the event and send events out of order; the invoice produces only one grant and an older
   subscription update cannot replace newer local state.
4. Advance the Test Clock through renewal; only the current period's characters are available and
   the previous period does not roll over.
5. Set period-end cancellation; the current paid period remains available and the local status is
   `CANCEL_AT_PERIOD_END`.
6. Create a refund, then void or mark an invoice uncollectible as a correction. Remaining available
   characters are revoked while committed usage stays non-negative.

The Testcontainers implementation test `DemonstrationSubscriptionProjectionITest` runs the same
signed payload, inbox, projection, admission, renewal, cancellation, refund, and correction
contract deterministically in CI.
