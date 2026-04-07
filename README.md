# NiFi GCP OAuth2 Access Token Provider

A custom Apache NiFi Controller Service that provides OAuth 2.0 access tokens for:

- **Google REST APIs** (Search Console, BigQuery, Pub/Sub, and any Google Cloud API)
- **GCP Managed Kafka** via SASL/OAUTHBEARER using Application Default Credentials (ADC) or Workload Identity Federation (WIF)

Compatible with **Apache NiFi 2.8.0**.

---

## Overview

Apache NiFi ships with `GCPCredentialsControllerService` to manage Google credentials, but does not provide a built-in bridge between those credentials and Kafka's SASL/OAUTHBEARER mechanism.

This NAR adds the `GCPOauth2AccessTokenProvider` controller service which:

1. Reads credentials from the existing `GCPCredentialsControllerService` (ADC, WIF, service account key, etc.)
2. Optionally impersonates a service account or applies domain-wide delegation
3. Returns a raw GCP access token for use with **InvokeHTTP** and other HTTP processors
4. Optionally wraps the token in a **pseudo-JWT** format required by **Kafka's OAUTHBEARER** mechanism (GCP Managed Kafka)

The Kafka pseudo-JWT follows the format defined by Google's official [`GcpLoginCallbackHandler`](https://github.com/googleapis/managedkafka):

```
Base64URL({"typ":"JWT","alg":"GOOG_OAUTH2_TOKEN"})
  . Base64URL({"exp":...,"iat":...,"scope":"kafka","sub":"<service-account>"})
  . Base64URL(<raw-gcp-access-token>)
```

---

## Requirements

- Apache NiFi **2.8.0**
- Java **21**
- Maven **3.9+** (or use the included `mvnw` wrapper)

---

## Installation

### Option A — Build from source

```bash
# Clone the repository
git clone https://github.com/<your-username>/nifi-gcp-oauth2-access-token.git
cd nifi-gcp-oauth2-access-token

# Build (skip tests for a clean build)
./mvnw clean install -DskipTests

# Copy the NAR to your NiFi extensions directory
cp nifi-gcp-oauth2-provider-nar/target/nifi-gcp-oauth2-provider-nar-2.8.0.nar $NIFI_HOME/nar_extensions/
```

Then **restart NiFi**.

### Option B — Download from Releases

Download the latest `.nar` file from the [Releases page](../../releases/latest) and copy it to `$NIFI_HOME/nar_extensions/`. Restart NiFi.

---

## Configuration

### Step 1 — GCP Credentials

Create and enable a **GCPCredentialsControllerService**.

This service manages how NiFi authenticates to GCP. Supported methods:
- **Application Default Credentials (ADC)** — recommended for GKE / Compute Engine (leave all fields blank)
- **Workload Identity Federation (WIF)** — for GKE Autopilot or external clusters
- **Service Account JSON key file** — for local development

### Step 2 — GCPOauth2AccessTokenProvider

Create a **GCPOauth2AccessTokenProvider** controller service and configure:

| Property | Description | Default |
|---|---|---|
| **GCP Credentials Provider Service** | The `GCPCredentialsControllerService` to use | *(required)* |
| **Scope** | Whitespace-delimited OAuth2 scopes | `https://www.googleapis.com/auth/cloud-platform` |
| **Impersonate Service Account** | Target service account email to impersonate | *(optional)* |
| **Domain-wide delegation** | User email for G Suite domain-wide delegation | *(optional)* |
| **Project ID** | Quota project for IAM Credentials API calls | *(optional)* |
| **Kafka Mode (OAUTHBEARER)** | Wrap token in pseudo-JWT for Kafka. Set `true` for Kafka, `false` for REST APIs | `false` |

### Step 3a — For Kafka (GCP Managed Kafka)

Configure a **Kafka3ConnectionService**:

| Property | Value |
|---|---|
| **Bootstrap Servers** | `bootstrap.<cluster-id>.<region>.managedkafka.<project-id>.cloud.goog:9092` |
| **Security Protocol** | `SASL_SSL` |
| **SASL Mechanism** | `OAUTHBEARER` |
| **OAuth2 Access Token Provider** | your `GCPOauth2AccessTokenProvider` (with **Kafka Mode = true**) |

> **IAM Role required:** The service account must have `roles/managedkafka.client` on the GCP project.

### Step 3b — For Google REST APIs (InvokeHTTP)

Create an **InvokeHTTP** processor and set:

| Property | Value |
|---|---|
| **Request OAuth2 Access Token Provider** | your `GCPOauth2AccessTokenProvider` (with **Kafka Mode = false**) |
| **HTTP Method** | As per the API documentation |
| **Remote URL** | The Google API endpoint |

---

## How it Works — Kafka Token Wrapping

GCP access tokens are **opaque strings** (`ya29.xxx...`). Kafka's `OAuthBearerLoginModule` expects a **JWT** with three Base64URL-encoded parts separated by dots.

When **Kafka Mode** is enabled, this provider wraps the GCP access token into a pseudo-JWT that is parseable by Kafka but carries the real GCP credential in the signature field:

```
Header:    {"typ":"JWT","alg":"GOOG_OAUTH2_TOKEN"}
Payload:   {"exp":<unix_epoch>,"iat":<unix_epoch>,"scope":"kafka","sub":"<service-account-email>"}
Signature: <raw-GCP-access-token>
```

This is the same approach used by Google's official [`managed-kafka-auth-login-handler`](https://github.com/googleapis/managedkafka) library.

---

## Building

```bash
# Full build with tests
./mvnw clean install

# Build without tests (faster)
./mvnw clean install -DskipTests

# Clean only
./mvnw clean
```

The resulting NAR file is at:
```
nifi-gcp-oauth2-provider-nar/target/nifi-gcp-oauth2-provider-nar-2.8.0.nar
```

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).

---

## Credits

Originally based on the [nifi-gcp-oauth2-access-token](https://github.com/Digital-Loop/nifi-gcp-oauth2-access-token) project by [Digital Loop GmbH](https://digital-loop.com).

Extended with GCP Managed Kafka SASL/OAUTHBEARER support and compatibility with Apache NiFi 2.8.0.
