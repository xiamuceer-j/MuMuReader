# MuMuReader

阅读 3 服务器版，一个自托管的网页阅读器，无需安装手机 App。

MuMuReader 兼容「阅读 3.0 / Legado」的书源规则，在浏览器中提供书架管理、在线搜索、章节阅读、换源、本地书籍导入、RSS 订阅与多用户等能力，适合部署在个人服务器或 NAS 上自用。

## 功能特性

- 书源管理与失效检测
- 书架、书籍分组与阅读进度同步
- 在线搜索与书海浏览
- 换源、并发搜书
- 多种翻页方式与手势支持
- 自定义主题、样式与文字替换过滤
- 本地 TXT / EPUB / UMD / PDF 导入
- WebDAV 同步与用户配置备份恢复
- RSS 订阅与定时更新书架
- 漫画、音频与听书（听书受浏览器限制）
- 多用户与权限管理
- 移动端适配与 PWA
- Kindle 阅读支持

## 技术栈

- 后端：Kotlin + Spring Boot + Vert.x，复用阅读 3.0 书源规则引擎
- 前端：Vue 2 + Vuex + Element UI（PWA）
- 桌面端：JavaFX（可选）
- 数据存储：文件存储，默认位于 `storage/` 目录

## 快速开始

使用 Docker Compose（推荐）：

```bash
cp .env.example .env
# 编辑 .env，设置 READER_APP_SECUREKEY 管理密码等
docker compose up -d
```

访问 `http://localhost:4396`。

更多安装方式（服务器 JAR、桌面端、源码编译、Nginx 反向代理等）请查看 [doc.md](doc.md)。

## 已知限制

- 部分使用 `Javascript` 的书源可能报错，例如调用原生 Java 等高级功能
- `webview` 功能需要另外部署远程接口，且不支持 `sourceRegex` 匹配资源响应
- 不支持书源登录功能

## 文档

- [使用与部署文档](doc.md)
- [界面预览](preview.md)

## 免责声明

本工具仅用于技术学习与个人试读，搜索与阅读内容均来自第三方网站，与 MuMuReader 无关。请尊重版权，支持正版。

## 许可证

[GPL-3.0](LICENSE)
