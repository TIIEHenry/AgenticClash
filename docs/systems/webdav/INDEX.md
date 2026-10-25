---
title: "WebDAV 同步"
type: index
status: accepted
phase: N/A
updated: 2026-10-24
summary: "配置与分流策略的拉取、推送和合并。etag 未变不下载正文，远端用量仍参与合并"
---

# WebDAV 同步

实现在 `service/src/main/java/com/github/kr328/clash/service/webdav/`。设置界面是 `app` 的 `WebdavSettingsActivity` 与 `design` 的 `WebdavSettingsDesign`。

## 权威常量

默认公共地址、局域网地址和用户名都为空，首次使用时由用户填写。默认远端路径在 `WebdavProtocol.kt` 的 `WEBDAV_DEFAULT_REMOTE_PATH`。本文不复制这些字面量。密码没有默认值。

设置存在应用文件目录的 `webdav/settings.json`。密码由 `WebdavCipher` 密封后再落盘，界面只显示已设置或未设置，不回填明文。编辑密码时输入框从空开始：留空确认保持原密码，点「清除」写入空串，输入新值则覆盖。`autoSync` 默认关闭。

密文读不出来（Keystore 密钥失效、换机或恢复备份）时，`WebdavCipher.open` 返回 null，与「本来没有密码」的空串区分开。界面写「无法读取，请重新输入」，地址、用户名和远端路径照常保留。写入时只有显式改密码才重新密封，其余字段的写入沿用磁盘上的原密文，不会把读不出的密码固化成空。

公共 URL 必须是 HTTPS。`requirePublicWebdavUrl` 拒绝公网 HTTP。局域网地址在 `joinWebdavUrl(..., allowPrivateHttp = true)` 下可以是私网 HTTP，因此 `app/src/main/res/xml/network_security_config.xml` 允许明文。公网 HTTP、`..` 和会改掉源站的路径仍然被拒绝。

是否探测局域网由 `prefersWebdavLan` 决定：当前要有一条具备 `TRANSPORT_WIFI` 的网络。探测是一次 2.5 秒超时的 PROPFIND，不带认证。只有带 DAV 头、207，或带认证要求的 401/403 才选用局域网。超时、拒绝连接、DNS 失败，以及普通网页的 200/404 都改用公共地址。同一组地址在 `WEBDAV_ROUTE_MEMO_MS` 内不再探测。局域网地址为空时直接用公共地址。没有公共地址且探测失败时，同步失败。

## 拉取与推送

`WebdavSync` 的拉取应用到本机，不把本机配置再上传。推送合并后上传，不把远端结果写回本机已导入的配置、访问控制或本地删除记录。

手动同步与自动同步共用一把锁，串行执行。同一订阅 URL 在两侧都存在时，保留本机 UUID。正文谁留下由 `syncRev`、`updatedAt`、`writerId`、内容哈希依次比较，相等时两边得到同一结果。流量在 `_sync.json` 的 `profileUsage` 里单独比较，不因此重传配置正文。配置文件里的流量数字只在 `profileUsage` 没有这份配置时才参与比较。

PROPFIND 的 etag 与上次成功同步记下的 etag 一致，并且本机还有这份配置时，跳过该文件的 GET。跳过正文时，远端 `_sync.json` 里同一 uuid 的用量仍进入合并；之后上传 meta 写出的是合并后的用量。etag 缺失必须下载。空正文和解析失败记为不可读，不删除，也不覆盖。能删的远端文件带 `If-Match`；412 只重试冲突的那一个文件或 `_sync.json`，最多 3 次。GET、PUT、DELETE 的并发是 `WEBDAV_HTTP_CONCURRENCY`，遇到 429 或 423 降到 `WEBDAV_HTTP_CONCURRENCY_THROTTLED`。

`mergeWebdav` / `mergeStrategies` 按同一侧重复项保留更新的 `updatedAt`，再比较 UUID。上传时删除远端未被保留、且这次能读的 UUID 文件。

自动同步里，本地改动经过 `WEBDAV_PUSH_DEBOUNCE_MS` 再做一次同步。连续失败 3 次以后改为按 `WEBDAV_PULL_INTERVAL_MS` 再试。服务启动后的周期任务只拉取，间隔也是 `WEBDAV_PULL_INTERVAL_MS`，不受这次推送失败的影响。打开自动同步仍立刻同步。名单和分流策略的变更只调度这次同步，页面不再等待它结束。

访问控制离开页面时的自动同步条件见 [访问控制](../access-control/INDEX.md)。

## 测试

`service/src/test/java/com/github/kr328/clash/service/webdav/` 里的 `WebdavSyncTest` 与 `WebdavClientTest` 覆盖路径、局域网选择、合并、删除记录和 ETag。命令见 [构建指南](../../guides/getting-started/build.md)。

模块入口：[service](../../modules/service/INDEX.md)、[app](../../modules/app/INDEX.md)。
