package su.xash.engine.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import su.xash.engine.R
import su.xash.engine.model.Game
import su.xash.engine.model.GameLibDownloader
import java.text.DateFormat
import java.util.Date

class GameSettingsPreferenceFragment(val game: Game) : PreferenceFragmentCompat() {
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		preferenceManager.sharedPreferencesName = game.basedir.name;
		setPreferencesFromResource(R.xml.game_preferences, rootKey);

		val packageList = findPreference<ListPreference>("package_name")!!
		packageList.entries = arrayOf(getString(R.string.app_name))
		packageList.entryValues = arrayOf(requireContext().packageName)

		if (packageList.value == null) {
			packageList.setValueIndex(0);
		}

		if (game.basedir.name.equals("cstrike", ignoreCase = true)
			|| game.basedir.name.equals("czero", ignoreCase = true)) {
			val enableYaPBBots = findPreference<SwitchPreferenceCompat>("enable_yapb_bots")!!
			enableYaPBBots.isVisible = true
		}

		// Android 17+ broke the legacy GLES1 driver path that NanoGL uses,
		// so don't let the user pick a renderer that is guaranteed to crash.
		if (Build.VERSION.SDK_INT >= 37) {
			val renderer = findPreference<ListPreference>("renderer")!!
			renderer.entries = arrayOf(
				getString(R.string.game_settings_renderer_auto),
				getString(R.string.game_settings_renderer_gl4es),
				getString(R.string.game_settings_renderer_gles3),
				getString(R.string.game_settings_renderer_soft)
			)
			renderer.entryValues = arrayOf("auto", "gl4es", "gles3compat", "soft")
			if (renderer.value == "gles1")
				renderer.value = "auto"
		}

		populateDownloadedBuildInfo()

		val customResolution = findPreference<EditTextPreference>("custom_resolution")!!
		updateCustomResolutionSummary(customResolution)
		customResolution.setOnPreferenceChangeListener { pref, newValue ->
			val text = (newValue as? String).orEmpty().trim()

			if (text.isEmpty()) {
				pref.summary = getString(R.string.game_settings_custom_resolution_none)
				true
			} else {
				val parsed = Game.parseCustomResolution(text)
				if (parsed == null) {
					Toast.makeText(requireContext(),
						R.string.game_settings_custom_resolution_invalid,
						Toast.LENGTH_LONG).show()
					false
				} else {
					pref.summary = getString(R.string.game_settings_custom_resolution_value,
						parsed.first, parsed.second)
					true
				}
			}
		}

		val separatePackages = findPreference<SwitchPreferenceCompat>("separate_libraries")!!
		val clientPackage = findPreference<ListPreference>("client_package")!!
		val serverPackage = findPreference<ListPreference>("server_package")!!
		separatePackages.setOnPreferenceChangeListener { _, newValue ->
			if (newValue == true) {
				packageList.isVisible = false
				clientPackage.isVisible = true
				serverPackage.isVisible = true
			} else {
				packageList.isVisible = true
				clientPackage.isVisible = false
				serverPackage.isVisible = false
			}

			true
		}
	}

	private fun updateCustomResolutionSummary(pref: EditTextPreference) {
		val parsed = Game.parseCustomResolution(pref.text)
		pref.summary = if (parsed != null)
			getString(R.string.game_settings_custom_resolution_value, parsed.first, parsed.second)
		else
			getString(R.string.game_settings_custom_resolution_none)
	}

	private fun populateDownloadedBuildInfo() {
		val downloader = GameLibDownloader(requireContext())
		val source = downloader.getSourceInfo(game.basedir.name) ?: return
		val downloadedAt = downloader.getDownloadTime(game.basedir.name)

		val urlPref = findPreference<Preference>("source_url")!!
		urlPref.isVisible = true
		urlPref.summary = source.url ?: "—"
		urlPref.isEnabled = source.url != null
		urlPref.setOnPreferenceClickListener {
			source.url?.let {
				startActivity(Intent(Intent.ACTION_VIEW, it.toUri()))
			}
			true
		}

		val branchPref = findPreference<Preference>("source_branch")!!
		branchPref.isVisible = true
		branchPref.summary = source.branch ?: "—"

		val commitPref = findPreference<Preference>("source_commit")!!
		commitPref.isVisible = true
		commitPref.summary = source.commit ?: "—"
		commitPref.isEnabled = source.commit != null && source.url != null
		commitPref.setOnPreferenceClickListener {
			// FIXME: GitHub-styled URL!
			val target = "${source.url!!.trimEnd('/')}/commit/${source.commit}"
			startActivity(Intent(Intent.ACTION_VIEW, target.toUri()))
			true
		}

		val timePref = findPreference<Preference>("downloaded_at")!!
		timePref.isVisible = true
		timePref.summary = if (downloadedAt > 0L)
			DateFormat.getDateTimeInstance().format(Date(downloadedAt))
		else
			"—"
	}
}
