# Security Policy

## Supported versions

Security fixes are provided for the current Copper product release identified by its immutable
release manifest. A frontend commit or image tag by itself does not define a supported product
version.

## Reporting a vulnerability

Do not open a public Issue with exploit details, customer data, credentials, tokens, private
keys, backups, screenshots, or production logs.

Use the private report form at
<https://github.com/soyia/copper_deploy/security/advisories/new>. Include the affected Copper
product version, component, reproducible steps using synthetic data, and impact. Remove or mask
all customer-identifying information before sending evidence.

If the private form is unavailable, open a public Issue containing only the words
`Security contact requested`; maintainers will arrange a private channel. Do not include any
technical details in that Issue.

## Response rules

- Critical reports are triaged within 72 hours; High within 7 calendar days.
- Acknowledgement is not a promise that a report is valid or exploitable.
- Fixes follow immutable release, backup, migration, and customer-controlled upgrade rules.
- Existing tags, image digests, SBOMs, and security evidence are never overwritten.
