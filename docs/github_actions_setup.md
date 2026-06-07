# GitHub Actions 配置说明

## 1. fork 仓库先手动启用 Actions

原作者说“fork 仓库要手动开一下 action 功能”，意思很简单：

- 你自己的 fork 仓库也能直接用 GitHub Actions 编译。
- 但 GitHub 对 fork 默认比较保守，第一次通常要手动进仓库 `Actions` 页面点一下启用。

## 2. 上游自动同步

仓库新增了：

- `.github/workflows/sync-upstream.yml`

行为：

- 支持手动触发；
- 每 6 小时自动执行一次；
- 从 `WEP-56/EZVenera` 抓取 `main`；
- 以 merge 的方式合入你 fork 的 `main`，不会直接硬覆盖你自己的提交。

## 3. iOS IPA 构建

仓库新增了：

- `.github/workflows/ios-ipa.yml`

行为：

- 支持手动触发；
- 推送 `v*` tag 时自动构建；
- 成功后上传未签名 `ipa`、`Runner.app` 压缩包和 `sha256`；
- tag 触发时会把这些产物附加到 GitHub Release。

### 当前产物说明

- `EZVenera-<version>-ios-unsigned.ipa`
  - 未签名 `ipa`
  - 适合后续用你自己的签名工具二次签名
- `EZVenera-<version>-ios-runner-app.zip`
  - 原始 `Runner.app` 压缩包
  - 适合你自己做更细的重签或排错

### 不再需要 Secrets

- 当前这条工作流不需要 Apple 证书、描述文件或 Team ID。
- 如果以后你要在 GitHub Actions 里直接产出“可安装成品 ipa”，再补签名版工作流就行。

## 4. 关于 iOS 16.3.1

这套改动的目标不是把最低版本抬到 `iOS 16.3.1`，而是保证：

- 不把最低支持版本设到高于 `iOS 16.3.1`；
- 避开当前代码里对 iOS 不支持的目录选择 / 文件夹直开逻辑；
- 让 iOS 端可以正常参与 GitHub Actions 构建链路。

## 5. 关于“未签名 ipa 后续再签”

这条路是成立的，但要注意两点：

- GitHub Action 产出的 `unsigned ipa` 不能直接安装到 iPhone。
- 你后续必须用你自己的证书和描述文件把它重新签好，设备才会认可。
