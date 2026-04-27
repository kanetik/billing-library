# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial bootstrap from `michal-luszczuk/MakeBillingEasy` (Apache-2.0).
- Substantial rewrite for Google Play Billing Library 8.x:
  - `enableAutoServiceReconnection`, sub-response code support, `enableOneTimeProducts`.
  - Typed exception hierarchy (`BillingException`) with `RetryType` classification.
  - Lifecycle-aware connection sharing via `BillingConnectionLifecycleManager`.
  - Per-call exponential backoff and hardened `launchFlow` error mapping.
