# M6-02 GitHub 平台清理申请

> 状态：已由仓库所有者提交 GitHub Support case `#4575811`（2026-07-16，Open）；不是工程完成证据，也不代表平台对象已经清理
> 关联 Issue：[`soyia/copper_web#59`](https://github.com/soyia/copper_web/issues/59)
> 最后核验：2026-07-16

## 1. 需要 GitHub 处理的范围

Copper 已完成三仓普通 GitHub 引用、标签和 Release 的历史重置与清理，但 GitHub 仍托管关闭 PR 的 `refs/pull/*/head`、PR 页面和缓存。普通 Git 客户端没有删除这些平台对象的权限。

| 仓库 | 普通远端分支 | 已抓取 PR head | 严格审计历史阻塞 |
| --- | ---: | ---: | ---: |
| `soyia/copper_web` | 1（`main`） | 41 | 18 |
| `soyia/copper_backend` | 1（`main`） | 20 | 26 |
| `soyia/copper_deploy` | 1（`main`） | 54 | 4 |
| 合计 | 3 | 115 | 48 |

Deploy 的 6 个旧标签、5 个 prerelease 和 22 个资产已删除。三个全新、非浅、单分支 `main` 克隆严格审计均为零；48 项仅在显式抓取历史 PR head 后出现。

## 2. 可直接提交给 GitHub Support 的申请文本

```text
Subject: Request to purge sensitive historical objects retained by closed pull-request refs

I am the owner of soyia/copper_web, soyia/copper_backend, and soyia/copper_deploy.

We completed an authorized history rewrite and removed ordinary branches, tags, and legacy prereleases because historical pull-request heads may contain personal-information and credential-pattern findings. Fresh clones of the current main branch in all three repositories pass a strict scan with zero findings.

GitHub-hosted closed pull-request head refs remain reachable. In an isolated audit clone, we explicitly fetched refs/pull/*/head and found 48 redacted historic findings across 115 PR heads. No raw secret or personal-information value is included in this request.

Please advise and perform the supported permanent removal or cache purge procedure for the obsolete pull-request Git objects and related cached/diff views, and confirm what remains externally reachable after completion. We can provide a private redacted audit report, affected object identifiers, and rewrite evidence through the support case.

Repositories:
- soyia/copper_web: 41 PR heads, 18 historic findings
- soyia/copper_backend: 20 PR heads, 26 historic findings
- soyia/copper_deploy: 54 PR heads, 4 historic findings
```

## 3. 提交与验收规则

1. [x] 已通过仓库所有者账户提交 GitHub Support case `#4575811`；提交内容不含原始秘密、个人信息、完整路径或未脱敏报告。
2. [x] 已将 case 编号和提交状态回填到 Issue #59；GitHub 的后续书面答复及受控私有证据仍需继续记录。
3. [ ] GitHub 处理后，在全新隔离克隆重新抓取 `refs/pull/*/head`，运行 M6-02 strict 审计。
4. [ ] 只有三仓普通引用、PR refs、待发布 Release 候选和客户源码包均为 `PASS`，才可关闭 M6-02。GitHub 仅说明缓存策略、但未清理或无法证明旧对象不可访问时，继续保持阻塞。
