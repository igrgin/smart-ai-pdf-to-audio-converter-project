import type { FormEvent, ReactNode } from "react";
import type { CsrfProof } from "../api";

export function CsrfForm({
  action,
  csrf,
  className,
  children,
  beforeSubmit,
  onSubmitError
}: {
  action: string;
  csrf: CsrfProof;
  className?: string;
  children: ReactNode;
  beforeSubmit?: () => Promise<void>;
  onSubmitError?: () => void;
}) {
  const submit = (event: FormEvent<HTMLFormElement>) => {
    if (!beforeSubmit) return;
    event.preventDefault();
    const form = event.currentTarget;
    void beforeSubmit().then(() => form.submit()).catch(() => onSubmitError?.());
  };
  return (
    <form action={action} method="post" className={className} onSubmit={submit}>
      <input type="hidden" name={csrf.parameterName} value={csrf.token} />
      {children}
    </form>
  );
}
