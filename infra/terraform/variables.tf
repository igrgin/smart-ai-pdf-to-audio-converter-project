variable "project_id" {
  description = "Disposable Google Cloud project that owns the environment."
  type        = string
}

variable "environment_name" {
  description = "Short disposable environment name, such as pr-123."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,15}$", var.environment_name))
    error_message = "environment_name must be 2-16 lowercase letters, numbers, or hyphens and start with a letter."
  }
}

variable "region" {
  description = "Cohesive EU deployment region."
  type        = string
  default     = "europe-west1"
}

variable "core_image" {
  description = "Immutable Artifact Registry image reference for the core and worker jobs."
  type        = string

  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.core_image))
    error_message = "core_image must be pinned by sha256 digest."
  }
}

variable "zitadel_issuer" {
  description = "EU-hosted ZITADEL custom-domain origin, without a trailing slash."
  type        = string

  validation {
    condition     = can(regex("^https://[^/]+$", var.zitadel_issuer))
    error_message = "zitadel_issuer must be an HTTPS origin without a path or trailing slash."
  }
}

variable "zitadel_client_id" {
  description = "OIDC web application client ID registered in ZITADEL."
  type        = string
}

variable "zitadel_client_secret_id" {
  description = "Secret Manager secret ID containing the ZITADEL OIDC client secret."
  type        = string
}
