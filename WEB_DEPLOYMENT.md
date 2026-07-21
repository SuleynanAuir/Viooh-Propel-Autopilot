# Propel Web 部署说明

这个版本把桌面操作改成了一个单页 Web 工具，同时继续使用原有 Java 合并、预算分配和 Apache POI Excel 生成逻辑。

## 运行结构

- Cloudflare Static Assets：托管 `src/main/resources/web` 下的页面。
- Cloudflare Worker：只把 `/api/*` 请求路由给 Container。
- Cloudflare Container：运行 Java 21 服务，接收上传并生成 XLSX。
- 每个任务的输入、PICS 和结果会保存在 Container 的临时目录；响应完成后立即清理。

当前 `wrangler.jsonc` 使用 `standard-1`（4 GiB）Container，并把单实例并发导出限制为 1，避免多个 Apache POI 工作簿同时占满内存。

## 本地运行

### 方式一：Docker

```bash
docker build -t viooh-propel-autopilot .
docker run --rm -p 8080:8080 viooh-propel-autopilot
```

浏览器打开 `http://localhost:8080`。

### 方式二：Maven

```bash
mvn -DskipTests package
java -cp target/Auto_project-1.0-SNAPSHOT.jar com.autoproject.web.WebMain
```

## Cloudflare 部署

前提：Workers Paid 计划、Node.js/npm、Docker Desktop，以及已经登录 Wrangler。

```bash
npm install
npx wrangler login
npm run deploy
```

`wrangler deploy` 会构建 `linux/amd64` Container 镜像、上传静态资源并部署 Worker。部署完成后 Wrangler 会输出公网地址。绑定自定义域名时，在 Cloudflare 控制台为这个 Worker 添加 Custom Domain 即可。

Cloudflare 控制台如果显示正在执行 `npm run build`，说明当前项目走的是 Pages/普通构建流程；这个流程只能构建静态资源，不能发布本项目依赖的 Java Container 后端。`npm run build` 会把前端文件复制到 `dist/`，用于满足 Pages 的输出目录检查；完整工具部署仍必须使用 `npm run deploy` 或把 Workers Builds 的部署命令设置为：

```bash
npx wrangler deploy
```

### Workers Builds 连接 GitHub

如果使用 Cloudflare 控制台连接 GitHub 仓库自动构建，Worker 名称必须与 `wrangler.jsonc` 里的 `name` 保持一致。本仓库当前使用：

```text
viooh-propel-autopilot
```

构建日志中如果仍然出现 `config file is using the Worker name "propel-web"`，说明 Cloudflare 正在构建旧提交；把包含 `wrangler.jsonc` 修改的提交推送到 GitHub 后重新部署。

如果 Maven 与 Docker 镜像都已经 `BUILD SUCCESS`，最后失败为：

```text
✘ [ERROR] Unauthorized
```

这表示部署步骤的 Cloudflare 身份没有足够权限，通常不是代码编译错误。当前日志已经显示 `mvn -DskipTests package` 和 Container 镜像构建都成功，失败发生在 Wrangler 把 Container/Worker 发布到 Cloudflare 时。

Workers Builds 连接 GitHub 时，建议不要只依赖默认授权；为这个项目配置一个自定义 Cloudflare API token，并确保 token 至少包含：

| Scope | Permission |
| --- | --- |
| Account | `Workers Scripts:Edit` |
| Account | `Containers:Edit` |
| Account | `Account Settings:Read` |
| User | `User Details:Read` |

如果项目绑定了自定义域名或路由，还需要对应 zone 的 `Workers Routes:Edit` 权限。然后在 Cloudflare Workers Builds 项目设置里把部署 token 更新为这个自定义 token，重新触发部署。

如果改用本机或其他 CI 部署，使用同一个有权限的 token：

```bash
export CLOUDFLARE_ACCOUNT_ID=你的账号ID
export CLOUDFLARE_API_TOKEN=你的API_TOKEN
npm run deploy
```

## 重要的上传限制

Cloudflare 对进入 Worker 的单次请求体按账户计划限额：Free/Pro 为 100 MB、Business 为 200 MB、Enterprise 默认 500 MB。仓库内现有 `Frames-details.csv` 大约 174 MiB，因此：

- Free/Pro 无法直接上传这个文件。
- Business 仅在整次 multipart 请求不超过 200 MB 时可用。
- 如果还要同时上传较大的 frame lists 或 PICS，建议 Enterprise，或者后续改成 R2 直传 + 异步任务架构。

Java 服务自身默认接受 250 MiB，可通过 `PROPEL_MAX_UPLOAD_BYTES` 调整，但该值不能突破 Cloudflare 账户层的请求体限制。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | ---: | --- |
| `PORT` | `8080` | Container HTTP 端口 |
| `PROPEL_MAX_UPLOAD_BYTES` | `262144000` | multipart 请求最大字节数 |
| `PROPEL_MAX_CONCURRENT_EXPORTS` | `1` | 单 Container 同时生成的工作簿数量 |
| `PROPEL_ALLOW_REMOTE_IMAGES` | `false` | 是否允许从 `FRAMEIMAGEPATH` URL 下载图片 |

公网部署应保持 `PROPEL_ALLOW_REMOTE_IMAGES=false`。如果确实要开启，只应处理可信输入，并在后续增加域名白名单；否则远程 URL 会带来 SSRF 风险。用户仍可通过页面上传本地 PICS 文件夹。

## 健康检查

```bash
curl https://你的域名/api/health
```

成功时会返回当前上传限制和远程图片开关。页面会读取这个接口并同步显示可用功能。
