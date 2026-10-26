# Third-party source scope

The direct third-party implementation in this source tree is the Caddy entry point at copper-web/docker/caddy/main.go (upstream v2.11.7, commit 72dd0fb067f6d7826c7f79907670ba4a713bfe37). Its Matthew Holt/The Caddy Authors copyright header and adjacent Apache-2.0 LICENSE and NOTICE remain intact.

| Preserved original | SHA256 |
|---|---|
| copper-web/docker/caddy/main.go | ac320c3ac47ad8abed7a0d2595639d78f9ff55cf1dba8494a66cd8851c5d076e |
| copper-web/docker/caddy/LICENSE | cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30 |
| copper-web/docker/caddy/NOTICE | 1e8070f17d98fe6156a6aaa937039e5985c0ae94853096de49e85113f4b4bef3 |

The Maven POM, pnpm lockfile and Caddy go.mod/go.sum refer to separately downloaded dependencies; they do not relicense those dependencies as Copper-owned code. This release does not vendor their implementations or include compiled products, OCI images, runtime databases, dependency caches or the previous internal installer medium. Building and later redistributing those products creates its own applicable notice/source obligations.

Retained engineering material limitations: de-indent@1.0.2 and lodash-unified@1.0.3 declare MIT in exact package metadata but their publisher license-body/holder material was not closed; github.com/smallstep/linkedca@v0.26.0 has the original Apache-2.0 text with holder not stated; github.com/zeebo/blake3@v0.2.4 has original CC0 material with holder not stated. These four unique components/six scopes remain REVIEW_REQUIRED in historical records. No publisher text or holder was invented. Their implementations are not included in this source edition.

Historical M4/M6 and Docker vendor security/material reports retain their original scope and statuses. Publishing this source does not declare a new binary security scan, complete M6 legal approval, or eliminate the separately recorded Docker vendor 6 Critical/29 High findings. The official runtime/installer binaries carrying those components are not release assets here.

The copper product name and simple first-party assets remain subject to TRADEMARKS.md for endorsement/branding. Third-party websites linked in historical business references are not incorporated website snapshots or an authorization to redistribute that site's content.
