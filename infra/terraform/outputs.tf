output "artifact_registry_repository" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.containers.repository_id}"
}

output "core_service_name" {
  value = google_cloud_run_v2_service.core.name
}

output "core_url" {
  value = google_cloud_run_v2_service.core.uri
}

output "firebase_site_id" {
  value = google_firebase_hosting_site.web.site_id
}

output "firebase_url" {
  value = "https://${google_firebase_hosting_site.web.site_id}.web.app"
}

output "offline_authorization_kms_key_version" {
  value = "${google_kms_crypto_key.capabilities.id}/cryptoKeyVersions/1"
}

output "offline_authorization_public_key" {
  value = replace(replace(replace(
    data.google_kms_crypto_key_version.offline_authorization.public_key[0].pem,
    "-----BEGIN PUBLIC KEY-----", ""),
  "-----END PUBLIC KEY-----", ""), "\n", "")
}
