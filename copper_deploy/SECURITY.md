# Copper Security Policy

## Supported versions

The supported product version is the component combination in the current immutable
`copper-release/v1` manifest. Individual branch heads, mutable image tags, locally edited
Compose files, and unlisted component combinations are not supported releases.

## Private vulnerability reporting

Use GitHub's private report form:

<https://github.com/soyia/copper_deploy/security/advisories/new>

Include the product version, affected component, synthetic reproduction steps, impact, and any
known workaround. Do not submit customer data, production backups, credentials, tokens, private
keys, complete Cookie values, personal information, bank accounts, or unredacted production logs.

If the private form is unavailable, open a public Issue containing only
`Security contact requested`. Maintainers will arrange a private channel. Never include exploit
details in that public Issue.

## Response and disclosure

- Critical reports are triaged within 72 hours; High within 7 calendar days.
- Confirmed issues are assigned an owner, severity, remediation deadline, and affected release
  range. False positives require written technical evidence and an expiry date.
- Critical fixes target 72 hours and High fixes target 14 calendar days. A time-limited exception
  does not change the severity.
- Security fixes follow immutable release, append-only migration, backup, and customer-controlled
  upgrade rules. Existing tags, image digests, migrations, SBOMs, and evidence are never replaced.
- Coordinated disclosure happens only after affected customers have a safe upgrade or documented
  mitigation. No report may publish customer-identifying information.
