package cn.wty5.term

import android.app.Application
import android.content.Context
import cn.wty5.term.terminal.TermConfig
import java.io.InputStream

class TermApp : Application() {

    companion object {
        // 全局单例实例
        private lateinit var instance: TermApp

        /**
         * 获取 Application 实例
         */
        fun getInstance(): TermApp {
            return instance
        }

        /**
         * 获取全局 Application Context
         */
        fun getAppContext(): Context {
            return instance.applicationContext
        }

        fun openAssets(path: String): InputStream{
            return instance.assets.open(path)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化全局配置或第三方库
        initThirdPartyLibraries()
    }

    /**
     * 用于初始化第三方 SDK 或全局组件（例如：Timber 日志、网络请求框架、数据库等）
     */
    private fun initThirdPartyLibraries() {
        TermConfig.init(this)
    }
}