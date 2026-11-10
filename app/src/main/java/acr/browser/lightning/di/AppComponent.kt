package acr.browser.lightning.di

import acr.browser.lightning.BrowserApp
import acr.browser.lightning.adblock.allowlist.SessionAllowListModel
import acr.browser.lightning.adblock.easylist.EasyListAdBlocker
import acr.browser.lightning.device.BuildInfo
import acr.browser.lightning.preference.UserPreferencesDataStore
import android.app.Application
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class, AppBindsModule::class, Submodules::class])
interface AppComponent {

    @Component.Builder
    interface Builder {

        @BindsInstance
        fun application(application: Application): Builder

        @BindsInstance
        fun buildInfo(buildInfo: BuildInfo): Builder

        @BindsInstance
        fun incognitoMode(@FullIncognitoMode incognitoMode: Boolean): Builder

        fun build(): AppComponent
    }

    fun inject(app: BrowserApp)

    /** Shared adblock engine for the experimental Gecko screen (same rules/toggles). */
    fun easyListAdBlocker(): EasyListAdBlocker

    fun userPreferencesDataStore(): UserPreferencesDataStore

    fun sessionAllowListModel(): SessionAllowListModel

    fun browserComponentBuilder(): BrowserComponent.Builder

    fun settingsComponentBuilder(): SettingsComponent.Builder

}

@Module(subcomponents = [BrowserComponent::class, SettingsComponent::class])
internal class Submodules
