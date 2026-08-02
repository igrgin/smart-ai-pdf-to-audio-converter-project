import { Button } from "../ui";
import { CsrfForm } from "./components/CsrfForm";
import type { CsrfProof } from "./api";

const providers = ["google", "apple", "facebook"] as const;

export function SignInDialog({ csrf, onClose }: { csrf?: CsrfProof; onClose: () => void }) {
  return (
    <div className="dialog-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="sign-in-dialog" role="dialog" aria-modal="true" aria-labelledby="sign-in-title" onMouseDown={(event) => event.stopPropagation()}>
        <button className="dialog-close" type="button" onClick={onClose} aria-label="Close sign-in methods">×</button>
        <span className="card-kicker">EU-hosted secure broker</span>
        <h2 id="sign-in-title">Choose a sign-in method</h2>
        <p>ZITADEL verifies your provider and requires TOTP before Folio opens your private Library.</p>
        <div className="provider-list">
          {providers.map((provider) => (
            <Button asChild variant="outline" key={provider}>
              <a href={`/oauth2/authorization/${provider}`}>Continue with {providerLabel(provider)}</a>
            </Button>
          ))}
        </div>
        {csrf && (
          <CsrfForm action="/api/v1/auth/recovery" csrf={csrf}>
            <button className="recovery-link" type="submit">Recover access securely</button>
          </CsrfForm>
        )}
        <small>Email is contact metadata only. Matching email addresses never merge Listener identities.</small>
      </section>
    </div>
  );
}

function providerLabel(provider: string) {
  return provider.charAt(0).toUpperCase() + provider.slice(1).toLowerCase();
}
