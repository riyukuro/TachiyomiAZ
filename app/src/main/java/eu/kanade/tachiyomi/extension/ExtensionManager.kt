package eu.kanade.tachiyomi.extension

import android.content.Context
import android.graphics.drawable.Drawable
import com.elvishew.xlog.XLog
import com.jakewharton.rxrelay.BehaviorRelay
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.api.ExtensionGithubApi
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.lang.launchNow
import eu.kanade.tachiyomi.util.system.toast
import exh.EH_SOURCE_ID
import exh.EIGHTMUSES_SOURCE_ID
import exh.EXH_SOURCE_ID
import exh.HBROWSE_SOURCE_ID
import exh.HITOMI_SOURCE_ID
import exh.MERGED_SOURCE_ID
import exh.NHENTAI_SOURCE_ID
import exh.PERV_EDEN_EN_SOURCE_ID
import exh.PERV_EDEN_IT_SOURCE_ID
import exh.source.BlacklistedSources
import kotlinx.coroutines.async
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The manager of extensions installed as another apk which extend the available sources. It handles
 * the retrieval of remotely available extensions as well as installing, updating and removing them.
 * To avoid malicious distribution, every extension must be signed and it will only be loaded if its
 * signature is trusted, otherwise the user will be prompted with a warning to trust it before being
 * loaded.
 *
 * @param context The application context.
 * @param preferences The application preferences.
 */
class ExtensionManager(
    private val context: Context,
    private val preferences: PreferencesHelper = Injekt.get()
) {

    /**
     * API where all the available extensions can be found.
     */
    private val api = ExtensionGithubApi()

    /**
     * The installer which installs, updates and uninstalls the extensions.
     */
    private val installer by lazy { ExtensionInstaller(context) }

    /**
     * Relay used to notify the installed extensions.
     */
    private val installedExtensionsRelay = BehaviorRelay.create<List<Extension.Installed>>()

    private val iconMap = mutableMapOf<String, Drawable>()

    /**
     * List of the currently installed extensions.
     */
    var installedExtensions = emptyList<Extension.Installed>()
        private set(value) {
            field = value
            installedExtensionsRelay.call(value)
        }

    fun getAppIconForSource(source: Source): Drawable? {
        val pkgName = installedExtensions.find { ext -> ext.sources.any { it.id == source.id } }?.pkgName
        if (pkgName != null) {
            return iconMap[pkgName] ?: iconMap.getOrPut(pkgName) { context.packageManager.getApplicationIcon(pkgName) }
        }

        return when (source.id) {
            EH_SOURCE_ID -> context.getDrawable(R.mipmap.ic_ehentai_source)
            EXH_SOURCE_ID -> context.getDrawable(R.mipmap.ic_ehentai_source)
            PERV_EDEN_EN_SOURCE_ID -> context.getDrawable(R.mipmap.ic_perveden_source)
            PERV_EDEN_IT_SOURCE_ID -> context.getDrawable(R.mipmap.ic_perveden_source)
            NHENTAI_SOURCE_ID -> context.getDrawable(R.mipmap.ic_nhentai_source)
            HITOMI_SOURCE_ID -> context.getDrawable(R.mipmap.ic_hitomi_source)
            EIGHTMUSES_SOURCE_ID -> context.getDrawable(R.mipmap.ic_8muses_source)
            HBROWSE_SOURCE_ID -> context.getDrawable(R.mipmap.ic_hbrowse_source)
            MERGED_SOURCE_ID -> context.getDrawable(R.mipmap.ic_merged_source)
            else -> null
        }
    }

    /**
     * Relay used to notify the available extensions.
     */
    private val availableExtensionsRelay = BehaviorRelay.create<List<Extension.Available>>()

    /**
     * List of the currently available extensions.
     */
    var availableExtensions = emptyList<Extension.Available>()
        private set(value) {
            field = value
            availableExtensionsRelay.call(value)
            updatedInstalledExtensionsStatuses(value)
        }

    var unalteredAvailableExtensions = emptyList<Extension.Available>()

    /**
     * Relay used to notify the untrusted extensions.
     */
    private val untrustedExtensionsRelay = BehaviorRelay.create<List<Extension.Untrusted>>()

    /**
     * List of the currently untrusted extensions.
     */
    var untrustedExtensions = emptyList<Extension.Untrusted>()
        private set(value) {
            field = value
            untrustedExtensionsRelay.call(value)
        }

    /**
     * The source manager where the sources of the extensions are added.
     */
    private lateinit var sourceManager: SourceManager

    /**
     * Initializes this manager with the given source manager.
     */
    fun init(sourceManager: SourceManager) {
        this.sourceManager = sourceManager
        initExtensions()
        ExtensionInstallReceiver(InstallationListener()).register(context)
    }

    /**
     * Loads and registers the installed extensions.
     */
    private fun initExtensions() {
        val extensions = ExtensionLoader.loadExtensions(context)

        installedExtensions = extensions
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }
        installedExtensions
            .flatMap { it.sources }
            // overwrite is needed until the bundled sources are removed
            .forEach { sourceManager.registerSource(it, true) }

        untrustedExtensions = extensions
            .filterIsInstance<LoadResult.Untrusted>()
            .map { it.extension }
            .filterNotBlacklisted()
    }

    // EXH -->
    fun <T : Extension> Iterable<T>.filterNotBlacklisted(): List<T> {
        val blacklistEnabled = preferences.eh_enableSourceBlacklist().get()
        return filter {
            if (it.isBlacklisted(blacklistEnabled)) {
                XLog.d("[EXH] Removing blacklisted extension: (name: %s, pkgName: %s)!", it.name, it.pkgName)
                false
            } else true
        }
    }

    fun Extension.isBlacklisted(
        blacklistEnabled: Boolean =
            preferences.eh_enableSourceBlacklist().get()
    ): Boolean {
        return pkgName in BlacklistedSources.BLACKLISTED_EXTENSIONS && blacklistEnabled
    }
    // EXH <--

    /**
     * Returns the relay of the installed extensions as an observable.
     */
    fun getInstalledExtensionsObservable(): Observable<List<Extension.Installed>> {
        return installedExtensionsRelay.asObservable()
    }

    /**
     * Returns the relay of the available extensions as an observable.
     */
    fun getAvailableExtensionsObservable(): Observable<List<Extension.Available>> {
        return availableExtensionsRelay.asObservable()
    }

    /**
     * Returns the relay of the untrusted extensions as an observable.
     */
    fun getUntrustedExtensionsObservable(): Observable<List<Extension.Untrusted>> {
        return untrustedExtensionsRelay.asObservable()
    }

    /**
     * Finds the available extensions in the [api] and updates [availableExtensions].
     */
    fun findAvailableExtensions() {
        launchNow {
            val extensions: List<Extension.Available> = try {
                api.findExtensions().filterNotBlacklisted()
            } catch (e: Exception) {
                context.toast(e.message)
                emptyList()
            }

            unalteredAvailableExtensions = extensions
            availableExtensions = extensions.filterNotBlacklisted()
        }
    }

    /**
     * Sets the update field of the installed extensions with the given [availableExtensions].
     *
     * @param availableExtensions The list of extensions given by the [api].
     */
    private fun updatedInstalledExtensionsStatuses(availableExtensions: List<Extension.Available>) {
        if (availableExtensions.isEmpty()) {
            preferences.extensionUpdatesCount().set(0)
            return
        }

        val mutInstalledExtensions = installedExtensions.toMutableList()
        var changed = false

        for ((index, installedExt) in mutInstalledExtensions.withIndex()) {
            val pkgName = installedExt.pkgName
            val availableExt = unalteredAvailableExtensions.find { it.pkgName == pkgName }

            if (availableExt == null && !installedExt.isObsolete) {
                mutInstalledExtensions[index] = installedExt.copy(isObsolete = true)
                changed = true
            } else if (installedExt.isBlacklisted() && !installedExt.isRedundant) {
                mutInstalledExtensions[index] = installedExt.copy(isRedundant = true)
                changed = true
            } else if (availableExt != null) {
                val hasUpdate = availableExt.versionCode > installedExt.versionCode
                if (installedExt.hasUpdate != hasUpdate) {
                    mutInstalledExtensions[index] = installedExt.copy(hasUpdate = hasUpdate)
                    changed = true
                }
            }
        }
        if (changed) {
            installedExtensions = mutInstalledExtensions
        }
        updatePendingUpdatesCount()
    }

    /**
     * Returns an observable of the installation process for the given extension. It will complete
     * once the extension is installed or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be installed.
     */
    fun installExtension(extension: Extension.Available): Observable<InstallStep> {
        return installer.downloadAndInstall(api.getApkUrl(extension), extension)
    }

    /**
     * Returns an observable of the installation process for the given extension. It will complete
     * once the extension is updated or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be updated.
     */
    fun updateExtension(extension: Extension.Installed): Observable<InstallStep> {
        val availableExt = availableExtensions.find { it.pkgName == extension.pkgName }
            ?: return Observable.empty()
        return installExtension(availableExt)
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        installer.cancelInstall(extension.pkgName)
    }

    /**
     * Sets to "installing" status of an extension installation.
     *
     * @param downloadId The id of the download.
     */
    fun setInstalling(downloadId: Long) {
        installer.updateInstallStep(downloadId, InstallStep.Installing)
    }

    fun setInstallationResult(downloadId: Long, result: Boolean) {
        val step = if (result) InstallStep.Installed else InstallStep.Error
        installer.updateInstallStep(downloadId, step)
    }

    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        installer.updateInstallStep(downloadId, step)
    }

    /**
     * Uninstalls the extension that matches the given package name.
     *
     * @param pkgName The package name of the application to uninstall.
     */
    fun uninstallExtension(pkgName: String) {
        installer.uninstallApk(pkgName)
    }

    /**
     * Adds the given signature to the list of trusted signatures. It also loads in background the
     * extensions that match this signature.
     *
     * @param signature The signature to whitelist.
     */
    fun trustSignature(signature: String) {
        val untrustedSignatures = untrustedExtensions.map { it.signatureHash }.toSet()
        if (signature !in untrustedSignatures) return

        ExtensionLoader.trustedSignatures += signature
        val preference = preferences.trustedSignatures()
        preference.set(preference.get() + signature)

        val nowTrustedExtensions = untrustedExtensions.filter { it.signatureHash == signature }
        untrustedExtensions -= nowTrustedExtensions

        val ctx = context
        launchNow {
            nowTrustedExtensions
                .map { extension ->
                    async { ExtensionLoader.loadExtensionFromPkgName(ctx, extension.pkgName) }
                }
                .map { it.await() }
                .forEach { result ->
                    if (result is LoadResult.Success) {
                        registerNewExtension(result.extension)
                    }
                }
        }
    }

    /**
     * Registers the given extension in this and the source managers.
     *
     * @param extension The extension to be registered.
     */
    private fun registerNewExtension(extension: Extension.Installed) {
        if (extension.isBlacklisted()) {
            XLog.d("[EXH] Removing blacklisted extension: (name: String, pkgName: %s)!", extension.name, extension.pkgName)
            return
        }

        installedExtensions += extension
        extension.sources.forEach { sourceManager.registerSource(it) }
    }

    /**
     * Registers the given updated extension in this and the source managers previously removing
     * the outdated ones.
     *
     * @param extension The extension to be registered.
     */
    private fun registerUpdatedExtension(extension: Extension.Installed) {
        if (extension.isBlacklisted()) {
            XLog.d("[EXH] Removing blacklisted extension: (name: String, pkgName: %s)!", extension.name, extension.pkgName)
            return
        }

        val mutInstalledExtensions = installedExtensions.toMutableList()
        val oldExtension = mutInstalledExtensions.find { it.pkgName == extension.pkgName }
        if (oldExtension != null) {
            mutInstalledExtensions -= oldExtension
            extension.sources.forEach { sourceManager.unregisterSource(it) }
        }
        mutInstalledExtensions += extension
        installedExtensions = mutInstalledExtensions
        extension.sources.forEach { sourceManager.registerSource(it) }
    }

    /**
     * Unregisters the extension in this and the source managers given its package name. Note this
     * method is called for every uninstalled application in the system.
     *
     * @param pkgName The package name of the uninstalled application.
     */
    private fun unregisterExtension(pkgName: String) {
        val installedExtension = installedExtensions.find { it.pkgName == pkgName }
        if (installedExtension != null) {
            installedExtensions -= installedExtension
            installedExtension.sources.forEach { sourceManager.unregisterSource(it) }
        }
        val untrustedExtension = untrustedExtensions.find { it.pkgName == pkgName }
        if (untrustedExtension != null) {
            untrustedExtensions -= untrustedExtension
        }
    }

    /**
     * Listener which receives events of the extensions being installed, updated or removed.
     */
    private inner class InstallationListener : ExtensionInstallReceiver.Listener {

        override fun onExtensionInstalled(extension: Extension.Installed) {
            registerNewExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
        }

        override fun onExtensionUpdated(extension: Extension.Installed) {
            registerUpdatedExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
        }

        override fun onExtensionUntrusted(extension: Extension.Untrusted) {
            untrustedExtensions += extension
        }

        override fun onPackageUninstalled(pkgName: String) {
            unregisterExtension(pkgName)
            updatePendingUpdatesCount()
        }
    }

    /**
     * Extension method to set the update field of an installed extension.
     */
    private fun Extension.Installed.withUpdateCheck(): Extension.Installed {
        val availableExt = availableExtensions.find { it.pkgName == pkgName }
        if (availableExt != null && availableExt.versionCode > versionCode) {
            return copy(hasUpdate = true)
        }
        return this
    }

    private fun updatePendingUpdatesCount() {
        preferences.extensionUpdatesCount().set(installedExtensions.count { it.hasUpdate })
    }
}
