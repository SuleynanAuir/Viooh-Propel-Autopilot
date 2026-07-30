# Propel 一键运行程序打包与使用

## Windows 最终产物

默认打包会生成一个可直接发送的 Windows 安装文件：

```text
release\windows\propel-1.0.0.exe
```

- 使用者只需拿到这一个安装 `.exe`。
- 安装包已包含程序和专用 Java 运行时；使用者不用安装 Java、JDK 或 Maven。
- 支持 Windows 10/11 64 位。
- 安装后提供三个入口，默认入口与 macOS 一样使用本地网页版：

| 启动文件 | 功能 |
|---|---|
| `propel.exe` | 默认入口：启动本地网页后端并自动打开浏览器 |
| `propel-web.exe` | 与默认入口相同的网页版兼容入口 |
| `propel-desktop.exe` | 打开原桌面整合程序 |

`propel.exe` 和 `propel-web.exe` 默认使用 `http://127.0.0.1:8080`。如果 8080 已被占用，会自动尝试
8081–8099，必要时选择其他系统空闲端口，不需要再手动修改命令行。

> `jpackage` 的 Windows EXE 是安装包，不是可以从任意位置直接运行的单文件绿色程序。这样可以可靠地携带 Java 运行时，并避免误删 `runtime` 或 `app` 目录导致程序无法启动。

## 最简单的打包方式：GitHub Actions

项目已经提供 `.github/workflows/build-windows-exe.yml`，因此在 Mac 上开发也能生成 Windows EXE。

1. 将代码推送到 GitHub。
2. 打开仓库的 **Actions** 页面。
3. 选择 **Build Windows EXE**。
4. 点击 **Run workflow**。
5. 构建完成后，下载名为 **propel-Windows-exe** 的 Artifact。
6. 解压 Artifact，得到安装包和免安装包：

```text
propel-1.0.0.exe
propel-Windows-portable.zip
SHA256SUMS.txt
```

安装包会创建 `propel`、`propel-web` 和 `propel-desktop` 三个入口。免安装包完整解压后也包含这三个入口。工作流会先运行 Maven 测试，并实际启动 `propel.exe` 检查 `/api/health`，确认默认入口确实是网页版后才上传产物。推送形如 `v1.0.0` 的 tag 也会自动触发构建。

## 在 Windows 电脑本地打包

打包电脑需要：

- JDK 21，并设置 `JAVA_HOME`
- Maven 3.9 或更新版本
- WiX Toolset 3

安装 WiX（已安装 Chocolatey 时）：

```powershell
choco install wixtoolset --no-progress -y
```

在项目根目录运行：

```powershell
.\scripts\package-windows.ps1
```

也可以直接双击：

```text
scripts\build-windows-bundle.cmd
```

默认会执行测试并生成 `release\windows\propel-1.0.0.exe`。仅在临时诊断构建时跳过测试：

```powershell
.\scripts\package-windows.ps1 -SkipTests
```

## 备用：免安装绿色版

如果确实需要免安装版本：

```powershell
.\scripts\package-windows.ps1 -PackageType Portable
```

产物为：

```text
release\windows\propel-Windows-portable.zip
```

使用者必须完整解压 ZIP：

- 双击 `propel.exe` 一键打开本地网页版。
- `propel-web.exe` 是相同网页版的兼容入口。
- 双击 `propel-desktop.exe` 打开旧桌面版。

不能只把其中任意一个 EXE 单独复制出来，因为三个启动器都依赖同目录下的 `app` 和 `runtime`。

同时生成单文件安装包和绿色版：

```powershell
.\scripts\package-windows.ps1 -PackageType All
```

## 使用说明

1. 双击 `propel-1.0.0.exe` 完成安装。
2. 双击 `propel`，程序会启动本地后端并自动打开默认浏览器。
3. 在网页中导入 CSV、TSV 或 Excel 文件，填写预算并导出结果。
4. 需要原桌面界面时，双击 `propel-desktop`。
5. 如 Windows 显示“未知发布者”，点击 **更多信息** → **仍要运行**。这是未购买代码签名证书时的正常提示。

## macOS 一键网页版

macOS 不能运行 `.exe`，对应的一键程序是：

```text
release/macos/Propel Web.app
```

在项目根目录运行一次打包命令：

```bash
./scripts/package-macos-web.sh --skip-tests
```

以后直接双击 `Propel Web.app`，它会自动启动本地端口并打开浏览器。应用已包含专用 Java
运行时、网页前后端和 `feishu/supply_matrix.xlsx`，不需要再输入 `java -cp ...` 命令。
如果 Maven 不在 `PATH` 中，可以通过 `MAVEN_BIN=/完整路径/mvn` 指定。

## 常见问题

| 问题 | 处理方式 |
|---|---|
| `jpackage.exe was not found` | 安装完整 JDK 21，不要只装 JRE；把 `JAVA_HOME` 指向 JDK |
| `WiX Toolset 3 was not found` | 安装 WiX 3，重新打开 PowerShell 后再打包 |
| 双击旧 `propel.exe` 没反应 | 不要从旧绿色版文件夹单独复制 EXE；改用新的 `propel-1.0.0.exe` 安装包 |
| 8080 端口已经被占用 | `propel` 和 `propel-web` 会自动选择下一个可用本地端口并打开正确地址 |
| Windows 提示未知发布者 | 当前 EXE 未签名；可继续运行，正式外发时建议购买代码签名证书 |
| 需要修改版本号 | 运行 `.\scripts\package-windows.ps1 -AppVersion 1.1.0` |

## 技术入口

- 桌面程序入口：`com.autoproject.Main`
- 本地网页一键入口：`com.autoproject.web.WebLauncherMain`
- Windows 默认网页启动器：`propel.exe`
- Windows 兼容网页启动器：`propel-web.exe`
- Windows 旧桌面启动器：`propel-desktop.exe`
- macOS 网页启动器：`Propel Web.app`
- 仅旧桌面启动器固定传入：`--gui`
- Java 最大堆内存：`4 GB`
- Supply Matrix 会打包到应用内部，并通过 `$APPDIR` 固定定位
- Windows 升级 UUID 固定不变，因此未来版本可以覆盖升级
