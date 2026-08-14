package com.htmake.reader.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "reader.app")
class AppConfig {
    lateinit var storagePath: String // 存储路径
    var showUI = false // 是否显示UI
    var debug = false  // 是否调试web
    var packaged = false  // 是否打包为app
    var secure = false    // 是否启用登录鉴权
    var inviteCode = ""   // 注册邀请码
    var secureKey = ""    // 管理密码
    var cacheChapterContent = false // 是否缓存章节内容
    var userLimit = 50    // 用户上限
    var userBookLimit = 200    // 用户书籍上限
    var debugLog = false  // 调试日志
    var autoClearInactiveUser = 0  // 自动清理不活跃用户

    var exportUseReplace = false // 导出不使用净化
    var exportCharset = "UTF-8" // 导出字符集
    var exportNoChapterName = false // 不添加章节名
    var exportPictureFile = false // 导出图片

    // 封面代理相关
    var coverTimeout = 8000L        // 封面下载超时时间(毫秒)
    var coverMaxSize = 10485760L    // 封面最大字节数，超出则拒绝缓存
    var coverMaxRedirect = 5        // 封面下载最大重定向次数
    var coverAllowPrivateHost = false // 是否允许封面地址指向内网/回环地址(存在 SSRF 风险)
    var coverCacheExpireDays = 30   // 封面磁盘缓存保留天数，0 表示永不清理
    var coverCacheMaxSize = 536870912L // 封面磁盘缓存容量上限(字节)，0 表示不限制
    var coverCacheMaxAge = 86400L   // 封面响应 Cache-Control max-age 秒数
}
