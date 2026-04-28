# Docker 一键部署说明

这套 Docker 配置会一次启动：

- Spring Boot 应用
- PostgreSQL + pgvector
- Elasticsearch
- RabbitMQ

## 1. 本地或服务器准备

安装 Docker 和 Docker Compose 后，进入项目根目录。

```bash
docker --version
docker compose version
```

## 2. 创建环境变量文件

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Linux / macOS:

```bash
cp .env.example .env
```

然后编辑 `.env`，至少改这几项：

```env
ZHIPU_API_KEY=你的智谱APIKey
POSTGRES_PASSWORD=一个强密码
RABBITMQ_DEFAULT_PASS=一个强密码
```

## 3. 一键启动

```bash
docker compose up -d --build
```

第一次启动会下载基础镜像和 Maven 依赖，时间会比较久。

查看日志：

```bash
docker compose logs -f app
```

看到 Spring Boot 正常启动后，访问：

```text
http://localhost:8080/auth/login
```

如果在云服务器上访问，把 `localhost` 换成服务器公网 IP：

```text
http://服务器公网IP:8080/auth/login
```

## 4. 常用命令

停止：

```bash
docker compose down
```

重启：

```bash
docker compose restart
```

更新代码后重新构建：

```bash
docker compose up -d --build
```

查看所有服务状态：

```bash
docker compose ps
```

查看应用日志：

```bash
docker compose logs -f app
```

## 5. 数据保存在哪里

数据通过 Docker volume 持久化：

- `postgres_data`: 数据库
- `es_data`: Elasticsearch 索引
- `rabbitmq_data`: RabbitMQ 数据
- `uploads_data`: 上传文件

普通重启或重新构建不会删除这些数据。

如果你执行下面这个命令，数据会被一起删除：

```bash
docker compose down -v
```

## 6. 云服务器端口

云服务器安全组至少放行：

- `22`: SSH 登录
- `8080`: 临时访问应用
- `80` / `443`: 后续配置域名和 HTTPS 时使用

数据库、Elasticsearch、RabbitMQ 管理后台在当前配置里只绑定到服务器本机 `127.0.0.1`，默认不会直接暴露到公网。

## 7. RabbitMQ 管理后台

如果你在服务器上需要查看 RabbitMQ 管理后台，可以用 SSH 隧道：

```bash
ssh -L 15672:127.0.0.1:15672 root@服务器公网IP
```

然后本地打开：

```text
http://localhost:15672
```

账号密码使用 `.env` 里的：

```env
RABBITMQ_DEFAULT_USER
RABBITMQ_DEFAULT_PASS
```
