# Copper public source repository

This independent source release repository is authorized by the product owner and uses AGPL-3.0-only for original Copper code, subject to the third-party scope in LICENSING.md/NOTICE. The old private repositories and their platform histories are not imported. Their source commits are provenance, not required private clone dependencies. Root public license/reporting instructions govern this snapshot; component business and safety rules remain applicable.

Components: copper_backend/ (business rules/API), copper-web/ (UI), copper_deploy/ (single runtime/deployment definition). Preserve their existing boundaries and adjacent layout. Use 8193 for backend development and explicitly set COPPER_API_TARGET=http://127.0.0.1:8193 for frontend development. Never bind/replace a user's formal 8191. Do not run Docker on the developer Mac.

Preserve data, precision, posting/reversal/idempotency/audit invariants and all 15 configurable product categories. Protect unrelated dirty work and secrets. No Actions by default; personal commits include [skip ci]. Public release tag/source assets are immutable. Check SOURCE_ORIGIN.json and release/ before making claims about validated versions. The source publication is not a new binary/installer acceptance or a signed commercial service.
