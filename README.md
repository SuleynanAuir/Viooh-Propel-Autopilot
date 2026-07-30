# viooh-propel-autopilot

用于合并 Frame 数据、生成 Proposal/PICS 等 Excel Sheet 的桌面与本地网页工具。

程序已经包含 Java 运行环境。普通使用者不需要安装 Java、JDK、Maven、Node.js 或 Docker。

## 下载

前往 [GitHub Releases 最新版本](https://github.com/SuleynanAuir/viooh-propel-autopilot/releases/latest)，根据操作系统下载：

> 当前仓库为 Private。下载者必须先登录 GitHub，并且拥有本仓库的访问权限；未获授权的用户打开下载链接会显示 404。若要让任何人直接下载，需要由仓库管理员另行把仓库设为 Public，或通过其他渠道分发发行包。

| 操作系统 | 下载文件 | 使用方式 |
| --- | --- | --- |
| macOS（Apple Silicon） | [`Propel-Web-macOS-arm64.zip`](https://github.com/SuleynanAuir/viooh-propel-autopilot/releases/latest/download/Propel-Web-macOS-arm64.zip) | 解压后双击 `Propel Web.app` |
| Windows 10/11 x64 | [`propel-1.0.0.exe`](https://github.com/SuleynanAuir/viooh-propel-autopilot/releases/latest/download/propel-1.0.0.exe) | 运行安装程序，安装后从开始菜单启动 |
| Windows 10/11 x64 免安装版 | [`propel-Windows-portable.zip`](https://github.com/SuleynanAuir/viooh-propel-autopilot/releases/latest/download/propel-Windows-portable.zip) | 完整解压后双击程序 |

如果不确定：

- Mac 用户下载 `Propel-Web-macOS-arm64.zip`。
- Windows 用户推荐下载 `propel-1.0.0.exe`。
- 不希望执行安装向导的 Windows 用户下载便携版 ZIP。

## macOS 安装与启动

当前 macOS 版本仅支持 Apple Silicon（M1/M2/M3/M4 等 ARM64 芯片），不支持 Intel Mac。

1. 下载 `Propel-Web-macOS-arm64.zip`。
2. 双击 ZIP 完成解压。
3. 双击 `Propel Web.app`。
4. 程序会启动本地 Java 后端，并自动打开浏览器操作页面。

应用尚未使用 Apple Developer ID 完成 notarization。从 Chrome/GitHub 下载后，首次启动可能显示“Apple 无法检查其是否包含恶意软件”：

1. 在提示窗口点击“完成”。
2. 打开“系统设置 → 隐私与安全性”。
3. 向下找到“已阻止使用 Propel Web”，点击“仍要打开”。
4. 输入 Mac 登录密码，再次确认“打开”。以后启动通常不再提示。

也可以先按住 Control 键点击 `Propel Web.app`，选择“打开”。如果系统仍然只显示阻止提示，可把应用拖入“应用程序”文件夹，然后在终端运行：

```bash
xattr -dr com.apple.quarantine "/Applications/Propel Web.app"
open "/Applications/Propel Web.app"
```

该命令只应对从本仓库 Release 下载并通过 SHA-256 校验的应用使用。若要从根本上取消所有用户的 Gatekeeper 提示，发行包必须使用付费 Apple Developer ID 证书签名并提交 Apple notarization。

## Windows 安装版

1. 下载 `propel-1.0.0.exe`。
2. 双击并完成安装向导。
3. 安装后双击 `propel.exe`：它会启动与 macOS 版本相同的本地 Java 后端，并自动打开浏览器页面。
4. 另外保留两个兼容/备用入口：
   - `propel-web.exe`：与 `propel.exe` 相同的网页版兼容入口。
   - `propel-desktop.exe`：需要旧界面时使用的原桌面入口。

安装包已经包含专用 Java 运行时，不需要另外安装 Java。

由于当前没有 Windows 商业代码签名证书，Microsoft Defender SmartScreen 可能显示“Windows 已保护你的电脑”。确认文件来自本仓库 Release 后，点击“更多信息 → 仍要运行”。

## Windows 便携版

1. 下载 `propel-Windows-portable.zip`。
2. 右键选择“全部解压”，不要直接在 ZIP 压缩包内运行。
3. 打开解压后的 `propel` 文件夹。
4. 双击 `propel.exe`，即可启动与 macOS 相同的本地网页版。

`propel-web.exe` 是相同网页版的兼容入口；`propel-desktop.exe` 是旧桌面界面。便携版里的 EXE 依赖同目录下的 `app` 和 `runtime` 文件夹，不能只复制单个 EXE，也不要删除这些目录。

## PICS 与飞书图片

Supply Matrix 已包含在发行包内。受保护的飞书文件夹需要以下任意一组凭证才能下载并嵌入真实图片：

- Feishu `user_access_token` 或 `tenant_access_token`；
- Feishu App ID 和 App Secret。

本地网页启动器可以在 **PICS images** 区域配置凭证。凭证只保存在当前程序进程内，关闭程序后失效，不会写入生成的 Excel。

## 使用注意事项

- 本地网页默认使用 `127.0.0.1:8080`；端口被占用时会自动选择其他可用端口。
- 首次启动时，Windows 防火墙可能询问是否允许 Java/Propel 通信。仅需允许本机需要的网络访问。
- `.app`、安装版和便携版均已包含运行环境，但飞书等外部受保护资源仍需要有效访问凭证。
- 可通过 [`SHA256SUMS.txt`](https://github.com/SuleynanAuir/viooh-propel-autopilot/releases/latest/download/SHA256SUMS.txt) 校验下载文件完整性。

## 开发者文档

- [程序打包与分发说明](DISTRIBUTION.md)
- Windows EXE 构建脚本：`scripts/package-windows.ps1`
- macOS App 构建脚本：`scripts/package-macos-web.sh`
