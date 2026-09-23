package af.shizuku.manager.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.ProjectLinks
import timber.log.Timber

class AboutSettingsFragment : BaseSettingsFragment() {

    private var versionClickCount = 0

    override fun getTitle(): CharSequence? = getString(R.string.settings_about)

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_about, rootKey)
        val context = requireContext()

        // Show the developer options entry only when already unlocked.
        findPreference<Preference>("nav_developer_options")?.isVisible = ShizukuSettings.isVectorEnabled()

        // 1. Setup Version with developer mode Easter Egg
        findPreference<Preference>("version")?.apply {
            summary = BuildConfig.VERSION_NAME
            setOnPreferenceClickListener {
                if (ShizukuSettings.isVectorEnabled()) {
                    Toast.makeText(context, R.string.settings_developer_options_revealed, Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }

                versionClickCount++
                if (versionClickCount >= 7) {
                    ShizukuSettings.setVectorEnabled(true)
                    SettingsSearchEngine.reset()
                    findPreference<Preference>("nav_developer_options")?.isVisible = true
                    Toast.makeText(context, R.string.settings_developer_options_revealed, Toast.LENGTH_SHORT).show()
                    versionClickCount = 0
                } else if (versionClickCount > 2) {
                    Toast.makeText(context, context.getString(R.string.settings_developer_options_click_more, 7 - versionClickCount), Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        // 2. Setup standard links
        findPreference<Preference>("changelog")?.setOnPreferenceClickListener {
            showChangelogDialog()
            true
        }

        findPreference<Preference>("website")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, ProjectLinks.WEBSITE)
            true
        }

        findPreference<Preference>("qq_group")?.setOnPreferenceClickListener {
            openQqGroup()
            true
        }

        findPreference<Preference>("source_code")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, ProjectLinks.REPOSITORY)
            true
        }

        findPreference<Preference>("open_source_licenses")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(requireContext(), ProjectLinks.OPEN_SOURCE_LICENSES)
            true
        }
    }

    private fun showChangelogDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.about_changelog_title)
            .setMessage(R.string.changelog_fallback_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun openQqGroup() {
        val context = context ?: return
        val qqIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=${ProjectLinks.QQ_GROUP_NUMBER}&card_type=group&source=qrcode")
        )
        try {
            startActivity(qqIntent)
        } catch (e: android.content.ActivityNotFoundException) {
            Timber.w(e, "QQ not installed, fallback to copy group number")
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QQ Group", ProjectLinks.QQ_GROUP_NUMBER))
            Toast.makeText(context, R.string.about_qq_group_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
