package acr.browser.lightning

import acr.browser.lightning.adblock.easylist.EasyListAdBlocker
import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.database.bookmark.BookmarkExporter
import acr.browser.lightning.database.bookmark.BookmarkRepository
import acr.browser.lightning.device.BuildInfo
import acr.browser.lightning.device.BuildType
import acr.browser.lightning.di.AppComponent
import acr.browser.lightning.di.DaggerAppComponent
import acr.browser.lightning.di.injector
import acr.browser.lightning.migration.Cleanup
import acr.browser.lightning.utils.FileUtils
import acr.browser.lightning.utils.LeakCanaryUtils
import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.webkit.WebView
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import dagger.Lazy
import java.io.File
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * The browser application.
 */
class BrowserApp : Application() {

    @Inject
    internal lateinit var leakCanaryUtils: LeakCanaryUtils

    @Inject
    internal lateinit var bookmarkModel: BookmarkRepository

    @Inject
    internal lateinit var buildInfo: BuildInfo

    @Inject
    internal lateinit var cleanup: Cleanup

    @Inject
    internal lateinit var appCoroutineScope: AppCoroutineScope

    @Inject
    internal lateinit var coroutineDispatchers: acr.browser.lightning.concurrency.CoroutineDispatchers

    @Inject
    internal lateinit var bookmarkExporter: BookmarkExporter

    @Inject
    internal lateinit var easyListAdBlocker: Lazy<EasyListAdBlocker>

    lateinit var applicationComponent: AppComponent

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            null
        }
        val isIncognito = processName == "$packageName:incognito"
        val isMainProcess = processName == null || processName == packageName
        if (!isMainProcess && !isIncognito) {
            // GeckoView spawns child processes (gpu, socket, crashhelper, …) that run this
            // same Application class. They must not touch WebView/DataStore/Dagger: WebView
            // crashes with a data-directory lock error when initialized twice.
            return
        }

        if (isIncognito) {
            File(dataDir, "app_webview_incognito").deleteRecursively()
            WebView.setDataDirectorySuffix("incognito")
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            if (BuildConfig.DEBUG) {
                FileUtils.writeCrashToStorage(ex)
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex)
            } else {
                exitProcess(2)
            }
        }

        applicationComponent = DaggerAppComponent.builder()
            .application(this)
            .buildInfo(createBuildInfo())
            .incognitoMode(isIncognito)
            .build()
        injector.inject(this)

        appCoroutineScope.launch {
            cleanup.cleanup()
        }

        // Pre-warm the ad blocker off the main thread so filter lists start downloading
        // immediately instead of racing the first page the user opens. Resolving the
        // Deferred early also means TabWebViewClient's bounded waits complete instantly
        // instead of stalling request threads on cold start.
        val warmBlocker = easyListAdBlocker
        appCoroutineScope.launch(coroutineDispatchers.io) {
            try {
                warmBlocker.get()
            } catch (_: Exception) {
            }
        }

        appCoroutineScope.launch {
            if (bookmarkModel.count() == 0L) {
                val assetsBookmarks = bookmarkExporter.importBookmarksFromAssets()
                bookmarkModel.addBookmarkList(assetsBookmarks)
            }
        }

        if (buildInfo.buildType == BuildType.DEBUG) {
            leakCanaryUtils.setup()
        }

        if (buildInfo.buildType == BuildType.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appCoroutineScope.cancel()
    }

    /**
     * Create the [BuildType] from the [BuildConfig].
     */
    private fun createBuildInfo() = BuildInfo(
        packageName = packageName,
        buildType = when {
            BuildConfig.DEBUG -> BuildType.DEBUG
            else -> BuildType.RELEASE
        },
        versionCode = BuildConfig.VERSION_CODE,
        isPlus = BuildConfig.FULL_VERSION,
    )

    companion object {
        private const val TAG = "BrowserApp"
    }
}
