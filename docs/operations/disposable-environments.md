# Disposable GCP and Firebase environments

The manual `Deploy disposable environment` workflow owns one isolated Terraform state prefix per
environment and deploys to `europe-west1`. It authenticates through GitHub OIDC; no service-account key
is stored in the repository.

Configure the GitHub `disposable` environment with these secrets:

- `GCP_WORKLOAD_IDENTITY_PROVIDER`: full workload identity provider resource name;
- `GCP_DEPLOYER_SERVICE_ACCOUNT`: deployer service-account email;
- `TF_STATE_BUCKET`: pre-created private GCS bucket for Terraform state.

The target project must be disposable, billing-enabled, and allow the deployer to enable APIs and
manage the resources declared under `infra/terraform`. Choose `apply` to bootstrap Artifact Registry,
publish the core image with SBOM/provenance, apply the private topology with the exact image digest,
build the PWA, and deploy Firebase Hosting. Choose `destroy` to remove the environment from its stored
state.

Terraform enforces an image reference ending in `@sha256:<digest>`. Cloud Run scales from zero to at
most three API instances; jobs are stage-specific and use a separate identity. Cloud SQL and both
object buckets have no public address or grant. Firebase routes `/api/**` to the fixed `folio-core`
service and serves the PWA shell from Hosting.

Disposable managed infrastructure is an engineering environment, not approval for public onboarding.
Legal, privacy, accessibility, penetration-testing, and critical/high remediation gates remain outside
this walking-skeleton ticket.
