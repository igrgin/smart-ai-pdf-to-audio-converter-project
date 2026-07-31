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
