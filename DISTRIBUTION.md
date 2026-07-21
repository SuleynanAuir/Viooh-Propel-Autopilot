# 记录：
打包说明：使用者环境不必安装Java编译器

## 结论

- **可以**：打好的包自带 Java 运行时，对方电脑**不用**单独安装 Java。
- **限制**：目前脚本生成的是 **Windows 64 位** 绿色版文件夹，不是单个极小 exe；请把整个文件夹打成 **zip** 再发送。
- **系统**：对方需要 **Windows 10/11（64 位）**。Mac/Linux 需另做打包。

## 打包流程（只需做一次）

1. 安装 **JDK 21**（不是 JRE），并保证命令行能执行：
   - `java -version`
   - `jpackage --version`
2. 在项目根目录打开 PowerShell，执行：

```powershell
.\scripts\package-windows.ps1
```

3. 若以前打包失败过，先清理嵌套目录（只需一次）：

```powershell
.\scripts\clean-package-artifacts.ps1
```

4. 成功后产物在：

```
dist\propel\
  propel.exe          ← 双击启动图形界面
  runtime\             ← 内置 Java，不要删
  app\                 ← 程序文件，不要删
```

5. 将 **`dist\propel` 整个文件夹** 压缩为 `propel-win.zip`，发给同事。

## 使用：

1. 解压 zip 到任意目录（路径尽量不要含奇怪符号）。
2. 双击 **`propel.exe`**（应出现 propel 图形窗口；若只有闪一下，说明用的是旧包，需用最新代码重新打包）。
3. 若 Windows 提示「未知发布者」：点 **更多信息** → **仍要运行**（未签名时常见）。
4. 按界面导入 CSV、填预算、导出 Excel（与开发环境用法相同）。

## 打包命令（仅脚本）

请使用 `scripts\package-windows.ps1`（会先 `mvn package` 再 `jpackage`）。不要用 `mvn clean` 与正在生成的 `target\dist` 同时进行。

## 常见问题

| 问题 | 说明 |
|------|------|
| `path exceeding 32000 characters` / 删不掉 `target\dist` | 旧版把输出放在 `target\dist` 且输入过整个 `target`，会产生 `app\dist\propel\...` 无限嵌套。先运行 `.\scripts\clean-package-artifacts.ps1`，再用最新脚本（输出在 **`dist\propel`**，输入只有 jar） |
| 打包机找不到 `jpackage` | 安装完整 **JDK 21**，把 `%JAVA_HOME%\bin` 加入 PATH |
| 同事双击没反应 | 是否只发了 `propel.exe` 没发整个文件夹；是否 32 位 Windows |
| 体积很大 | 正常，内含精简 JRE，约 80–150 MB |
| 需要安装版 `.msi` | 需额外安装 [WiX Toolset 3](https://wixtoolset.org/)，再用 `jpackage --type exe`（可后续再加） |

## 技术说明

- 程序入口：`com.autoproject.Main`（默认打开 **propel** 图形界面）。
- 普通 `mvn package` **不会**跑 jpackage（避免没有 JDK 的环境构建失败）；只有 `-Pwindows-app` 或上述脚本会生成 exe。
