locals {
  prefix                   = "folio-${var.environment_name}"
  site_id                  = substr("${var.project_id}-${var.environment_name}", 0, 30)
  inspection_push_audience = "https://${var.project_id}.internal/${var.environment_name}/inspection"
  worker_stages = toset([
    "inspection",
    "extraction",
    "narration-analysis",
    "speech",
    "packaging",
    "finalization",
    "erasure",
    "reconciliation"
  ])
  required_services = toset([
    "artifactregistry.googleapis.com",
    "cloudkms.googleapis.com",
    "compute.googleapis.com",
    "firebase.googleapis.com",
    "firebasehosting.googleapis.com",
    "iam.googleapis.com",
    "pubsub.googleapis.com",
    "run.googleapis.com",
    "cloudscheduler.googleapis.com",
    "secretmanager.googleapis.com",
    "servicenetworking.googleapis.com",
    "sqladmin.googleapis.com",
    "storage.googleapis.com"
  ])
}

data "google_project" "current" {
  project_id = var.project_id
}

resource "google_project_service" "required" {
  for_each = local.required_services

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository" "containers" {
  location      = var.region
  repository_id = "${local.prefix}-containers"
  format        = "DOCKER"
  description   = "Immutable application images for ${local.prefix}"

  depends_on = [google_project_service.required["artifactregistry.googleapis.com"]]
}

resource "google_compute_network" "private" {
  name                    = "${local.prefix}-private"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "private" {
  name                     = "${local.prefix}-private"
  region                   = var.region
  network                  = google_compute_network.private.id
  ip_cidr_range            = "10.42.0.0/24"
  private_ip_google_access = true
}

resource "google_compute_global_address" "private_services" {
  name          = "${local.prefix}-private-services"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.private.id

  depends_on = [google_project_service.required["servicenetworking.googleapis.com"]]
}

resource "google_service_networking_connection" "private_services" {
  network                 = google_compute_network.private.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_services.name]
}

resource "random_password" "database" {
  length  = 32
  special = true
}

resource "random_password" "inspection_database" {
  length  = 32
  special = true
}

resource "random_password" "upload_capability" {
  length  = 48
  special = false
}

resource "google_sql_database_instance" "postgres" {
  name                = "${local.prefix}-postgres"
  region              = var.region
  database_version    = "POSTGRES_17"
  deletion_protection = false

  settings {
    tier              = "db-custom-1-3840"
    availability_type = "ZONAL"
    disk_type         = "PD_SSD"
    disk_size         = 10
    disk_autoresize   = true

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "02:00"
    }

    ip_configuration {
      ipv4_enabled                                  = false
      private_network                               = google_compute_network.private.id
      enable_private_path_for_google_cloud_services = true
    }
  }

  depends_on = [
    google_project_service.required["sqladmin.googleapis.com"],
    google_service_networking_connection.private_services
  ]
}

resource "google_sql_database" "platform" {
  name     = "audiobook"
  instance = google_sql_database_instance.postgres.name
}

resource "google_sql_user" "platform" {
  name     = "audiobook"
  instance = google_sql_database_instance.postgres.name
  password = random_password.database.result
}

resource "google_sql_user" "inspection" {
  name     = "audiobook_inspection"
  instance = google_sql_database_instance.postgres.name
  password = random_password.inspection_database.result
}

resource "google_secret_manager_secret" "database_password" {
  secret_id = "${local.prefix}-database-password"
  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }

  depends_on = [google_project_service.required["secretmanager.googleapis.com"]]
}

resource "google_secret_manager_secret_version" "database_password" {
  secret      = google_secret_manager_secret.database_password.id
  secret_data = random_password.database.result
}

resource "google_secret_manager_secret" "inspection_database_password" {
  secret_id = "${local.prefix}-inspection-database-password"
  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }

  depends_on = [google_project_service.required["secretmanager.googleapis.com"]]
}

resource "google_secret_manager_secret_version" "inspection_database_password" {
  secret      = google_secret_manager_secret.inspection_database_password.id
  secret_data = random_password.inspection_database.result
}

resource "google_secret_manager_secret" "upload_capability" {
  secret_id = "${local.prefix}-upload-capability"
  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }

  depends_on = [google_project_service.required["secretmanager.googleapis.com"]]
}

resource "google_secret_manager_secret_version" "upload_capability" {
  secret      = google_secret_manager_secret.upload_capability.id
  secret_data = random_password.upload_capability.result
}

resource "google_storage_bucket" "working" {
  name                        = "${var.project_id}-${var.environment_name}-working"
  location                    = var.region
  force_destroy               = true
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  lifecycle_rule {
    condition { age = 23 }
    action { type = "Delete" }
  }
}

resource "google_storage_bucket" "finalized" {
  name                        = "${var.project_id}-${var.environment_name}-finalized"
  location                    = var.region
  force_destroy               = true
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  versioning { enabled = true }
}

resource "google_pubsub_topic" "work" {
  name       = "${local.prefix}-work"
  depends_on = [google_project_service.required["pubsub.googleapis.com"]]
}

resource "google_pubsub_subscription" "stages" {
  for_each = local.worker_stages

  name                       = "${local.prefix}-${each.key}"
  topic                      = google_pubsub_topic.work.id
  ack_deadline_seconds       = 600
  message_retention_duration = "604800s"

  retry_policy {
    minimum_backoff = "10s"
    maximum_backoff = "600s"
  }

  dynamic "push_config" {
    for_each = each.key == "inspection" ? [true] : []
    content {
      push_endpoint = "${google_cloud_run_v2_service.core.uri}/internal/v1/inspection-work-deliveries"
      oidc_token {
        service_account_email = google_service_account.worker.email
        audience              = local.inspection_push_audience
      }
    }
  }

  depends_on = [google_service_account_iam_member.pubsub_push_token_creator]
}

resource "google_kms_key_ring" "capabilities" {
  name     = "${local.prefix}-capabilities"
  location = var.region

  depends_on = [google_project_service.required["cloudkms.googleapis.com"]]
}

resource "google_kms_crypto_key" "capabilities" {
  name            = "signing"
  key_ring        = google_kms_key_ring.capabilities.id
  rotation_period = "7776000s"

  purpose = "ASYMMETRIC_SIGN"
  version_template {
    algorithm = "EC_SIGN_P256_SHA256"
  }

  lifecycle { prevent_destroy = false }
}

resource "google_service_account" "core" {
  account_id   = "${local.prefix}-core"
  display_name = "${local.prefix} core"
}

resource "google_service_account" "worker" {
  account_id   = "${local.prefix}-worker"
  display_name = "${local.prefix} workers"
}

resource "google_service_account" "inspection_worker" {
  account_id   = "${local.prefix}-inspection"
  display_name = "${local.prefix} inspection worker"
}

resource "google_service_account" "inspection_launcher" {
  account_id   = "${local.prefix}-inspect-run"
  display_name = "${local.prefix} inspection job launcher"
}

resource "google_secret_manager_secret_iam_member" "core_database_password" {
  secret_id = google_secret_manager_secret.database_password.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.core.email}"
}

resource "google_secret_manager_secret_iam_member" "core_zitadel_client_secret" {
  secret_id = var.zitadel_client_secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.core.email}"
}

resource "google_secret_manager_secret_iam_member" "core_upload_capability" {
  secret_id = google_secret_manager_secret.upload_capability.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.core.email}"
}

resource "google_secret_manager_secret_iam_member" "worker_database_password" {
  secret_id = google_secret_manager_secret.database_password.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.worker.email}"
}

resource "google_secret_manager_secret_iam_member" "inspection_database_password" {
  secret_id = google_secret_manager_secret.inspection_database_password.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.inspection_worker.email}"
}

resource "google_pubsub_topic_iam_member" "core_publisher" {
  topic  = google_pubsub_topic.work.name
  role   = "roles/pubsub.publisher"
  member = "serviceAccount:${google_service_account.core.email}"
}

resource "google_project_iam_member" "worker_subscriber" {
  project = var.project_id
  role    = "roles/pubsub.subscriber"
  member  = "serviceAccount:${google_service_account.worker.email}"
}

resource "google_service_account_iam_member" "pubsub_push_token_creator" {
  service_account_id = google_service_account.worker.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:service-${data.google_project.current.number}@gcp-sa-pubsub.iam.gserviceaccount.com"
}

resource "google_storage_bucket_iam_member" "worker_working_objects" {
  bucket = google_storage_bucket.working.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.worker.email}"
}

resource "google_project_iam_custom_role" "inspection_object_reader" {
  role_id     = "folio_inspection_reader_${replace(var.environment_name, "-", "_")}"
  title       = "${local.prefix} inspection object reader"
  description = "Read opaque quarantine objects without listing or mutation"
  permissions = ["storage.objects.get"]
}

resource "google_storage_bucket_iam_member" "inspection_working_objects" {
  bucket = google_storage_bucket.working.name
  role   = google_project_iam_custom_role.inspection_object_reader.name
  member = "serviceAccount:${google_service_account.inspection_worker.email}"
}

resource "google_storage_bucket_iam_member" "core_working_objects" {
  bucket = google_storage_bucket.working.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.core.email}"
}

resource "google_storage_bucket_iam_member" "worker_finalized_objects" {
  bucket = google_storage_bucket.finalized.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.worker.email}"
}

resource "google_storage_bucket_iam_member" "core_finalized_objects" {
  bucket = google_storage_bucket.finalized.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.core.email}"
}

resource "google_kms_crypto_key_iam_member" "core_signer" {
  crypto_key_id = google_kms_crypto_key.capabilities.id
  role          = "roles/cloudkms.signerVerifier"
  member        = "serviceAccount:${google_service_account.core.email}"
}

resource "google_cloud_run_v2_service" "core" {
  name     = "folio-core"
  location = var.region
  ingress  = "INGRESS_TRAFFIC_ALL"

  deletion_protection = false

  template {
    service_account                  = google_service_account.core.email
    timeout                          = "30s"
    max_instance_request_concurrency = 40

    scaling {
      min_instance_count = 0
      max_instance_count = 3
    }

    containers {
      image = var.core_image

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
        cpu_idle = true
      }

      ports { container_port = 8080 }

      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = "prod"
      }
      env {
        name  = "DATABASE_URL"
        value = "jdbc:postgresql://${google_sql_database_instance.postgres.private_ip_address}:5432/${google_sql_database.platform.name}"
      }
      env {
        name  = "DATABASE_USER"
        value = google_sql_user.platform.name
      }
      env {
        name  = "BUILD_VERSION"
        value = var.environment_name
      }
      env {
        name  = "BUILD_REVISION"
        value = substr(regex("sha256:([0-9a-f]{64})$", var.core_image)[0], 0, 12)
      }
      env {
        name  = "GOOGLE_CLOUD_PROJECT"
        value = var.project_id
      }
      env {
        name  = "WORKING_BUCKET"
        value = google_storage_bucket.working.name
      }
      env {
        name  = "WORK_TOPIC"
        value = google_pubsub_topic.work.name
      }
      env {
        name  = "PUBSUB_PUSH_AUDIENCE"
        value = local.inspection_push_audience
      }
      env {
        name  = "PUBSUB_PUSH_SERVICE_ACCOUNT"
        value = google_service_account.worker.email
      }
      env {
        name  = "TRACING_SAMPLE_RATE"
        value = "0.1"
      }
      env {
        name  = "APPLICATION_ORIGIN"
        value = "https://${google_firebase_hosting_site.web.site_id}.web.app"
      }
      env {
        name  = "ZITADEL_ISSUER"
        value = var.zitadel_issuer
      }
      env {
        name  = "ZITADEL_AUTHORIZATION_URI"
        value = "${var.zitadel_issuer}/oauth/v2/authorize"
      }
      env {
        name  = "ZITADEL_TOKEN_URI"
        value = "${var.zitadel_issuer}/oauth/v2/token"
      }
      env {
        name  = "ZITADEL_USERINFO_URI"
        value = "${var.zitadel_issuer}/oidc/v1/userinfo"
      }
      env {
        name  = "ZITADEL_JWK_SET_URI"
        value = "${var.zitadel_issuer}/oauth/v2/keys"
      }
      env {
        name  = "ZITADEL_RECOVERY_URI"
        value = "${var.zitadel_issuer}/ui/v2/login"
      }
      env {
        name  = "ZITADEL_CLIENT_ID"
        value = var.zitadel_client_id
      }
      env {
        name  = "ZITADEL_GOOGLE_IDP_ID"
        value = var.zitadel_google_idp_id
      }
      env {
        name  = "ZITADEL_APPLE_IDP_ID"
        value = var.zitadel_apple_idp_id
      }
      env {
        name  = "ZITADEL_FACEBOOK_IDP_ID"
        value = var.zitadel_facebook_idp_id
      }
      env {
        name = "DATABASE_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.database_password.secret_id
            version = "latest"
          }
        }
      }
      env {
        name = "ZITADEL_CLIENT_SECRET"
        value_source {
          secret_key_ref {
            secret  = var.zitadel_client_secret_id
            version = "latest"
          }
        }
      }
      env {
        name = "UPLOAD_TOKEN_SECRET"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.upload_capability.secret_id
            version = "latest"
          }
        }
      }

      startup_probe {
        initial_delay_seconds = 5
        timeout_seconds       = 3
        period_seconds        = 5
        failure_threshold     = 12
        http_get { path = "/actuator/health/readiness" }
      }

      liveness_probe {
        timeout_seconds   = 3
        period_seconds    = 15
        failure_threshold = 3
        http_get { path = "/actuator/health/liveness" }
      }
    }

    vpc_access {
      egress = "PRIVATE_RANGES_ONLY"
      network_interfaces {
        network    = google_compute_network.private.name
        subnetwork = google_compute_subnetwork.private.name
      }
    }
  }

  depends_on = [
    google_project_service.required["run.googleapis.com"],
    google_secret_manager_secret_iam_member.core_database_password,
    google_secret_manager_secret_iam_member.core_zitadel_client_secret,
    google_secret_manager_secret_iam_member.core_upload_capability,
    google_storage_bucket_iam_member.core_working_objects,
    google_sql_user.platform,
    google_sql_user.inspection
  ]
}

resource "google_cloud_run_v2_service_iam_member" "public_core" {
  project  = var.project_id
  location = google_cloud_run_v2_service.core.location
  name     = google_cloud_run_v2_service.core.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_cloud_run_v2_service_iam_member" "inspection_push_core" {
  project  = var.project_id
  location = google_cloud_run_v2_service.core.location
  name     = google_cloud_run_v2_service.core.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.worker.email}"
}

resource "google_cloud_run_v2_job" "workers" {
  for_each = local.worker_stages

  name     = "${local.prefix}-${each.key}"
  location = var.region

  deletion_protection = false

  template {
    task_count = 1
    template {
      service_account = each.key == "inspection" ? google_service_account.inspection_worker.email : google_service_account.worker.email
      max_retries     = each.key == "inspection" ? 0 : 3
      timeout         = each.key == "inspection" ? "600s" : "3600s"

      containers {
        image = var.core_image

        resources {
          limits = {
            cpu    = each.key == "extraction" ? "2" : "1"
            memory = each.key == "extraction" ? "2Gi" : (each.key == "inspection" ? "1Gi" : "512Mi")
          }
        }

        env {
          name  = "APP_MODE"
          value = "worker"
        }
        env {
          name  = "SPRING_MAIN_WEB_APPLICATION_TYPE"
          value = "none"
        }
        env {
          name  = "SPRING_PROFILES_ACTIVE"
          value = "prod"
        }
        env {
          name  = "BUILD_VERSION"
          value = var.environment_name
        }
        env {
          name  = "BUILD_REVISION"
          value = substr(regex("sha256:([0-9a-f]{64})$", var.core_image)[0], 0, 12)
        }
        env {
          name  = "FLYWAY_ENABLED"
          value = "false"
        }
        env {
          name  = "WORKER_STAGE"
          value = each.key
        }
        env {
          name  = "WORKER_IDLE"
          value = "false"
        }
        env {
          name  = "DATABASE_URL"
          value = "jdbc:postgresql://${google_sql_database_instance.postgres.private_ip_address}:5432/${google_sql_database.platform.name}"
        }
        env {
          name  = "DATABASE_USER"
          value = each.key == "inspection" ? google_sql_user.inspection.name : google_sql_user.platform.name
        }
        env {
          name  = "WORKING_BUCKET"
          value = google_storage_bucket.working.name
        }
        dynamic "env" {
          for_each = each.key == "inspection" ? [] : [true]
          content {
            name  = "FINALIZED_BUCKET"
            value = google_storage_bucket.finalized.name
          }
        }
        env {
          name  = "WORK_TOPIC"
          value = google_pubsub_topic.work.name
        }
        env {
          name  = "GOOGLE_CLOUD_PROJECT"
          value = var.project_id
        }
        env {
          name  = "PUBSUB_PUSH_AUDIENCE"
          value = local.inspection_push_audience
        }
        env {
          name  = "PUBSUB_PUSH_SERVICE_ACCOUNT"
          value = google_service_account.worker.email
        }
        env {
          name  = "UPLOAD_TOKEN_SECRET"
          value = "worker-does-not-mint-upload-capabilities"
        }
        env {
          name  = "APPLICATION_ORIGIN"
          value = "https://worker.invalid"
        }
        env {
          name  = "ZITADEL_ISSUER"
          value = "https://worker.invalid"
        }
        env {
          name  = "ZITADEL_AUTHORIZATION_URI"
          value = "https://worker.invalid/oauth/authorize"
        }
        env {
          name  = "ZITADEL_TOKEN_URI"
          value = "https://worker.invalid/oauth/token"
        }
        env {
          name  = "ZITADEL_USERINFO_URI"
          value = "https://worker.invalid/oauth/userinfo"
        }
        env {
          name  = "ZITADEL_JWK_SET_URI"
          value = "https://worker.invalid/oauth/keys"
        }
        env {
          name  = "ZITADEL_RECOVERY_URI"
          value = "https://worker.invalid/recovery"
        }
        env {
          name  = "ZITADEL_CLIENT_ID"
          value = "worker-does-not-authenticate"
        }
        env {
          name  = "ZITADEL_CLIENT_SECRET"
          value = "worker-does-not-authenticate"
        }
        env {
          name  = "ZITADEL_GOOGLE_IDP_ID"
          value = "worker-google-disabled"
        }
        env {
          name  = "ZITADEL_APPLE_IDP_ID"
          value = "worker-apple-disabled"
        }
        env {
          name  = "ZITADEL_FACEBOOK_IDP_ID"
          value = "worker-facebook-disabled"
        }
        env {
          name  = "OPERATOR_LISTENER_IDS"
          value = "00000000-0000-0000-0000-000000000000"
        }
        env {
          name  = "FREE_GRANT_CHARACTERS"
          value = "500000"
        }
        env {
          name  = "FREE_GRANT_VALIDITY"
          value = "365d"
        }
        env {
          name  = "PER_CONVERSION_CHARACTER_CEILING"
          value = "500000"
        }
        env {
          name  = "PER_CONVERSION_SPEND_CEILING_MICROS"
          value = "5000000"
        }
        env {
          name  = "PER_LISTENER_SPEND_CEILING_MICROS"
          value = "10000000"
        }
        env {
          name  = "PROVIDER_SPEND_CEILING_MICROS"
          value = "100000000"
        }
        env {
          name  = "GLOBAL_SPEND_CEILING_MICROS"
          value = "150000000"
        }
        env {
          name  = "PER_LISTENER_CONCURRENCY"
          value = "1"
        }
        env {
          name  = "GLOBAL_CONCURRENCY"
          value = "3"
        }
        env {
          name = "DATABASE_PASSWORD"
          value_source {
            secret_key_ref {
              secret  = each.key == "inspection" ? google_secret_manager_secret.inspection_database_password.secret_id : google_secret_manager_secret.database_password.secret_id
              version = "latest"
            }
          }
        }
      }

      vpc_access {
        egress = each.key == "inspection" ? "ALL_TRAFFIC" : "PRIVATE_RANGES_ONLY"
        network_interfaces {
          network    = google_compute_network.private.name
          subnetwork = google_compute_subnetwork.private.name
        }
      }
    }
  }

  depends_on = [
    google_project_service.required["run.googleapis.com"],
    google_secret_manager_secret_iam_member.worker_database_password,
    google_secret_manager_secret_iam_member.inspection_database_password,
    google_storage_bucket_iam_member.inspection_working_objects,
    google_sql_user.platform,
    google_sql_user.inspection
  ]
}

resource "google_cloud_run_v2_job_iam_member" "inspection_launcher" {
  project  = var.project_id
  location = google_cloud_run_v2_job.workers["inspection"].location
  name     = google_cloud_run_v2_job.workers["inspection"].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.inspection_launcher.email}"
}

resource "google_cloud_scheduler_job" "inspection" {
  name      = "${local.prefix}-inspection"
  region    = var.region
  schedule  = "* * * * *"
  time_zone = "Etc/UTC"

  http_target {
    http_method = "POST"
    uri         = "https://run.googleapis.com/v2/projects/${var.project_id}/locations/${var.region}/jobs/${google_cloud_run_v2_job.workers["inspection"].name}:run"
    body        = base64encode("{}")

    oauth_token {
      service_account_email = google_service_account.inspection_launcher.email
    }
  }

  depends_on = [
    google_project_service.required["cloudscheduler.googleapis.com"],
    google_cloud_run_v2_job_iam_member.inspection_launcher
  ]
}

resource "google_firebase_project" "platform" {
  provider = google-beta
  project  = var.project_id

  depends_on = [google_project_service.required["firebase.googleapis.com"]]
}

resource "google_firebase_hosting_site" "web" {
  provider = google-beta
  project  = var.project_id
  site_id  = local.site_id

  depends_on = [
    google_firebase_project.platform,
    google_project_service.required["firebasehosting.googleapis.com"]
  ]
}
