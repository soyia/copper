# Contributing to Copper

Use Issues for synthetic, reproducible functional problems and pull requests for focused changes. Report suspected security issues privately as described in SECURITY.md. Do not submit customer records, production configuration, credentials, backups, screenshots containing private data or unlicensed third-party material.

Contributions must be original or submitted with the right to contribute under AGPL-3.0-only, while preserving applicable third-party notices. Add a Signed-off-by line using git commit -s to certify the Developer Certificate of Origin 1.1: https://developercertificate.org/. A GitHub noreply identity is acceptable when it identifies the contributor. A sign-off is an origin certification, not a copyright transfer or automatic permission for commercial relicensing. No CLA or commercial dual license is introduced by this release.

Keep changes within the appropriate component and verify the frontend/backend contract for fields, states and flows. Preserve the 15-category product baseline, quantity/money precision, idempotency, posting, reversal and audit invariants. Existing component AGENTS.md business rules remain useful; historical prohibitions on public publication and private-origin delivery are superseded only for the public source grant identified by LICENSING.md and SOURCE_ORIGIN.json.

Use an isolated database and backend port 8193 for development. Do not stop, bind, restart or replace a user's port 8191 service. Do not run Docker on the developer's Mac for project acceptance. Use scoped local tests and record their actual limitations; do not claim skipped database/device acceptance as passed. CI/Actions is not a default completion gate; personal development commits include [skip ci].
