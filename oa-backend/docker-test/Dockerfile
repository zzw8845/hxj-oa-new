# ===========================================================================
#  海峡金 OA 后端镜像
#
#  【为什么显式写 --platform=linux/amd64】
#  构建机是 Apple Silicon(arm64)，服务器是 x86_64。不指定平台时 docker 会产出 arm64
#  镜像，push 到仓库后服务器拉下来直接报 "exec format error" ——
#  这个错误在容器启动瞬间才出现，排查时又很像"jar 坏了"，是典型的浪费半天。
#
#  【基础镜像只影响构建机，不影响服务器】
#  服务器拉的是我们 push 上去的成品镜像，基础镜像从哪来它并不关心。
#  因此这里用公开的 eclipse-temurin:17-jre-jammy（与本地开发用的 JDK 17 对齐）。
#  若 Docker Hub 拉取过慢，换成内网/云厂商的 JDK17 基础镜像即可，其余不用动。
# ===========================================================================
FROM --platform=linux/amd64 eclipse-temurin:17-jre-jammy

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
# start-period 给 90s：Flowable 首次启动要校验/建表，比普通 Web 应用慢得多，
# 这个值太小会让刚启动的容器被判成不健康，进而触发蓝绿切换的误判。
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=6 \
    CMD curl -sf http://127.0.0.1:8080/api/ping || exit 1

# 用 sh -c 才能展开 $JAVA_OPTS（给运维留一个不改镜像就能调堆大小/GC 的口子）；
# exec 让 java 顶替 sh 成为 PID 1，从而正确接收 docker stop 的 SIGTERM。
# 这一点对本项目是硬要求：Flowable 在途事务与 Hikari 连接池都需要优雅停机，
# 否则每次发版都会把正在审批中的请求硬砍断。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar /opt/oa/oa.jar --server.port=8080"]
