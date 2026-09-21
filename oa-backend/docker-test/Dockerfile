# ===========================================================================
#  海峡金 OA 后端镜像
#
#  【本镜像在**服务器上**构建，不在开发机构建】
#  最初的设计是"开发机 buildx --platform linux/amd64 构建 → push 到 SWR → 服务器 pull"。
#  实际部署时放弃这条路线，原因是**开发机的 Docker 坏了**：
#      docker pull 报
#      write /var/lib/desktop-containerd/daemon/io.containerd.metadata.v1.bolt/meta.db:
#      input/output error
#  Docker Desktop 的 containerd 元数据库损坏，任何 pull/build 都会失败，
#  而这台机器上没有第二条构建路径可走。
#
#  改用"服务器本地构建"后反而更简单：少一次 600MB 的镜像 push/pull，
#  也没有"开发机架构 ≠ 服务器架构"的坑（在 x86_64 上构建，天然就是 amd64）。
#
#  【基础镜像为什么不用 eclipse-temurin:17-jre-jammy】
#  实测服务器**拉不到 Docker Hub**：
#      Get "https://registry-1.docker.io/v2/": ... Client.Timeout exceeded while awaiting headers
#  daemon.json 里配了阿里云加速器（docker info 确认已生效、curl 该加速器返回 200），
#  但阿里云免费加速器已不再代理 `eclipse-temurin` 这类镜像，拉取时会回落到 Docker Hub 然后超时。
#  实测可用的是华为云公共镜像源（这台服务器本身已登录 SWR 华为云，网络可达）：
#      swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/eclipse-temurin:17-jre-jammy
#  因此把它作为 ARG 的默认值。换环境时用 --build-arg BASE_IMAGE=... 覆盖即可。
# ===========================================================================
ARG BASE_IMAGE=swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/eclipse-temurin:17-jre-jammy
FROM ${BASE_IMAGE}

LABEL org.opencontainers.image.title="haixiajin-oa" \
      org.opencontainers.image.description="海峡金 OA 审批系统后端"

# 时区必须显式设置：镜像默认 UTC，不设会让容器里的时间比北京时间早 8 小时。
# 后果不是"日志时间看着别扭"这么轻 —— 审批留痕、单据日期都取系统时间，
# 时区错了就是把错误的业务时间写进数据库。
#
# SPRING_PROFILES_ACTIVE=test 作为**默认值**写在这里，compose 里也显式设了一份。
# 两处都写是有意的：少一处漏配，应用就会退回默认 profile 去连 127.0.0.1:3306，
# 而 Hikari 是懒连接 —— 服务照常起来、每个查询都失败，正是本项目踩过的"假活"形态。
# 宁可默认就是对的，也不要靠"记得配"。
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=test

# curl 不是为了调试，是给下面的 HEALTHCHECK 用的。
# 装它换来的收益很实在：蓝绿切换可以等 docker 报告 healthy 再切 nginx，
# 而不是像以前那样 `docker logs | grep 'Started XxxApplication'` ——
# 那种按日志文本匹配的判定，一旦启动横幅改了措辞就会静默失效（本项目已中过一次招）。
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/oa

# 只拷 jar。配置一律走环境变量（SPRING_PROFILES_ACTIVE=test + .env），
# 不把任何 yml/env 打进镜像：同一个镜像要能在不同环境复用，
# 而且镜像层是会被 push 的，往里塞配置文件等于把凭据一起推上仓库。
COPY oa.jar /opt/oa/oa.jar

# 日志目录；compose 会把宿主机目录挂到这里
RUN mkdir -p /logs

# 仅作声明，实际端口由 --server.port 决定（沿用 8080）
EXPOSE 8080

# 健康检查打 /api/ping：它是免登录白名单里的轻量端点，只回 pong。
# start-period 给 120s：Flowable 首次启动要校验/建表，比普通 Web 应用慢得多，
# 而且这台服务器内存紧（可能触发 swap），启动比开发机慢。这个值太小会让刚启动的
# 容器被判成不健康，进而触发蓝绿切换的误判。
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=8 \
    CMD curl -sf http://127.0.0.1:8080/api/ping || exit 1

# 用 sh -c 才能展开 $JAVA_OPTS（给运维留一个不改镜像就能调堆大小/GC 的口子）；
# exec 让 java 顶替 sh 成为 PID 1，从而正确接收 docker stop 的 SIGTERM。
# 这一点对本项目是硬要求：Flowable 在途事务与 Hikari 连接池都需要优雅停机，
# 否则每次发版都会把正在审批中的请求硬砍断。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar /opt/oa/oa.jar --server.port=8080"]
