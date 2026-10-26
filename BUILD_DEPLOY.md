# 部署工具源码范围

本目录相邻的 copper-deploy/ 是唯一运行编排、配置模板及备份/恢复/升级实现。其既有一站式安装器、工厂及 source-rehearsal 工具保留 INTERNAL_REHEARSAL_ONLY 格式和原门槛；本次没有去掉检查或把原验收介质换名公开。它们的历史私有 clone 路径不是本仓源码构建前置。

可在本公开根目录验证源码，随后检查工具合同：

```sh
python3 scripts/verify-source-release.py
cd copper-deploy
bash tests/static-contract.sh
python3 -m unittest discover -s tests -p 'test_*.py'
```

部分真实容器/数据库/硬件验收是独立过程，勿将静态测试当成完整安装验收。正式容器验收使用已授权 Ubuntu 24.04 amd64 主机，不在开发Mac启动Docker，不映射宿主机8191或3306。

自行构建运行产品前，按 BUILD_BACKEND.md/BUILD_WEB.md 准备自有环境；容器的密码、持久目录、健康、备份、迁移和恢复规则继续查阅本组件文档。当前公开源码不是开箱即用的一站式产品包，也不宣称离线安装Docker、网络隔离整机安装或rc.10到rc.11直接升级通过。

本仓提供完整回归输入，包括组件原来 export-ignore 的历史govulncheck原件；公开打包时仅将这一打包属性显式关闭，未改变运行时代码。
