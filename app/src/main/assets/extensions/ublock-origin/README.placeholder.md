# 占位说明（不是扩展文件）

此目录在正式施工时放 **uBlock Origin** 官方 AMO 发布包解开后的原文件（manifest.json 等），
包名 id 须为 `uBlock0@raymondhill.net`。取件纪律：

- 只从 addons.mozilla.org 官方发布页取当前正式版 xpi，解包后逐文件核对与 xpi 一致；
- 扩展包内含 PNG 等二进制文件，**不走 GitHub 连接器推送**（连接器只写文本、二进制会损坏），
  走浏览器网页端上传通道（与图标 v3 同路）；
- 版本号写进 stage5 日志，升级＝换新包＋随 L 发版，ensureBuiltIn 按版本号自动就地升级。
