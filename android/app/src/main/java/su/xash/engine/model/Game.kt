package su.xash.engine.model

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import su.xash.engine.BuildConfig
import su.xash.engine.R
import su.xash.engine.XashActivity
import su.xash.engine.util.showDownloadProgressDialog
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale

class Game(val ctx: Context, val basedir: File, val gameInfoFile: File, previous: Game? = null) {
	private var iconName = "game.ico"
	var title = "Unknown Game"
	var icon: Bitmap? = null
	var cover: Bitmap? = null
	val iconSignature: String?
	val coverSignature: String?

	val mobileHacksGames = arrayOf("aom", "bdlands", "biglolly", "bshift", "caseclosed",
		"hl_urbicide", "induction", "redempt", "secret",
		"sewer_beta", "tot", "valve", "vendetta")

	// a1ba: follow the behavior of Xash3D's game_launch.
	// for hl mods we put `valve` as game directory
	// for any other game that's not hl this string must be replaced with your
	// main game directory
	// mods always use -game command line parameter
	var defaultGameDir = "valve"

	private val pref = ctx.getSharedPreferences(basedir.name, Context.MODE_PRIVATE)

	init {
		parseGameInfo(gameInfoFile)

		val iconFile = File(basedir, iconName)
		val iconSig = if (iconFile.isFile) {
			"${iconFile.absolutePath}:${iconFile.length()}:${iconFile.lastModified()}"
		} else {
			null
		}
		iconSignature = iconSig
		if (iconSig != null) {
			icon = if (previous != null && previous.iconSignature == iconSig) {
				previous.icon
			} else {
				BitmapFactory.decodeFile(iconFile.path)
			}
		}

		val coverSig = try {
			BackgroundBitmap.getBackgroundSignature(basedir)
		} catch (e: Exception) {
			Log.w(TAG, "Failed to fingerprint the background of $basedir", e)
			null
		}
		coverSignature = coverSig
		cover = if (coverSig != null && previous != null && previous.coverSignature == coverSig) {
			previous.cover
		} else {
			try {
				BackgroundBitmap.createBackground(basedir)
			} catch (e: Exception) {
				// Most mods have no background tiles at all, this is not an error.
				Log.d(TAG, "No background for $basedir: ${e.message}")
				null
			}
		}
	}

	fun startEngine(ctx: Context) {
		val packageNames = getPackageNamesForGameDir(basedir.name)
		var externalGame = false
		var commandLineArgs = ""

		if (basedir.name != defaultGameDir)
			commandLineArgs += "-game ${basedir.name} "

		if (packageNames != null) {
			if (packageNames.contains("su.xash.engine")) {
				commandLineArgs += "-dll @hl "
			} else if (packageNames.contains("su.xash.cs16client")) {
				if (pref.getBoolean("enable_yapb_bots", true)) {
					commandLineArgs += "-dll @yapb "
				}
				externalGame = true
			}
		}

		commandLineArgs += pref.getString("arguments", "-console -log") ?: ""

		// Video settings for the new engine. These are converted into engine
		// arguments so they are applied before/independent of the config file.
		val videoArgs = StringBuilder()

		val customResolution = parseCustomResolution(pref.getString("custom_resolution", ""))
		val targetHeight = pref.getString("resolution_height", "0")?.toIntOrNull() ?: 0
		val sceneScalingRequested = customResolution != null || targetHeight > 0

		// NanoGL/GLES1 and gl-wes builds compile out render target support, so
		// scene scaling silently does nothing there. Prefer GL4ES whenever a
		// non-native scene resolution is requested. Explicit GL4ES/GLES3
		// choices are left untouched; software rendering is left untouched too.
		var renderer = pref.getString("renderer", "auto")
		if (Build.VERSION.SDK_INT >= 37 && renderer == "gles1")
			renderer = "auto"
		if (sceneScalingRequested && (renderer.isNullOrEmpty() || renderer == "auto"
				|| renderer == "gles1" || renderer == "gles2"))
			renderer = "gl4es"

		if (!renderer.isNullOrEmpty() && renderer != "auto") {
			// the launcher's renderer choice must win over a stale -ref in the
			// user's command line (Sys_CheckParm returns the first match)
			commandLineArgs = commandLineArgs.replace(Regex("""(^|\s)-ref\s+\S+"""), " ")
			videoArgs.append("-ref ").append(renderer).append(' ')
		}

		// Show the build and the requested scene size for a few seconds, so it
		// is possible to confirm that this exact APK and setting are active.
		val sceneLabel = when {
			customResolution != null -> "3D ${customResolution.first}x${customResolution.second}"
			targetHeight > 0 -> "3D ${targetHeight}p"
			else -> "3D native"
		}
		Toast.makeText(ctx,
			"Xash3D ${BuildConfig.VERSION_NAME} | $sceneLabel | ${renderer ?: "auto"}",
			Toast.LENGTH_LONG).show()

		// Exact scene size, e.g. 860x605. r_scene_width/r_scene_height
		// override r_scene_scale in the renderer. These are passed as direct
		// "-" engine options because "+" commands are only executed through
		// "stuffcmds", which depends on the game's .rc file and may never run.
		if (customResolution != null) {
			videoArgs.append("-scene_width ").append(customResolution.first).append(' ')
			videoArgs.append("-scene_height ").append(customResolution.second).append(' ')
			// always reset the scale so a stale value from opengl.cfg can
			// never take over the exact size
			videoArgs.append("-scene_scale 1.0 ")
		} else {
			// Always clear exact-size overrides that may have been saved into
			// opengl.cfg by an older launch.
			videoArgs.append("-scene_width 0 -scene_height 0 ")

			val metrics = ctx.resources.displayMetrics
			val screenHeight = minOf(metrics.widthPixels, metrics.heightPixels)
			val scale = if (targetHeight > 0 && screenHeight > targetHeight)
				targetHeight.toFloat() / screenHeight
			else
				1.0f

			videoArgs.append("-scene_scale ")
				.append(String.format(Locale.US, "%.4f", scale))
				.append(' ')
		}

		// ask the renderer to confirm the effective scene/upscale size on screen
		if (sceneScalingRequested)
			videoArgs.append("-scene_info ")

		val msaa = pref.getString("msaa", "0")?.toIntOrNull() ?: 0
		if (msaa == 0 || msaa == 2 || msaa == 4 || msaa == 8 || msaa == 16)
			videoArgs.append("-msaa ").append(msaa).append(' ')

		if (videoArgs.isNotEmpty()) {
			if (commandLineArgs.isNotEmpty() && !commandLineArgs.endsWith(" "))
				commandLineArgs += " "
			commandLineArgs += videoArgs
		}

		if (externalGame && packageNames != null) {
			var packageName: String? = null
			var gameLibDir: String? = null

			for (pn in packageNames) {
				gameLibDir = try {
					getGameLibDir(ctx, pn)
				} catch (e: PackageManager.NameNotFoundException) {
					null
				} catch (e: Exception) {
					e.printStackTrace()
					null
				}

				if (gameLibDir != null) {
					packageName = pn
					break
				}
			}

			if (gameLibDir == null) {
				MaterialAlertDialogBuilder(ctx).apply {
					setTitle(R.string.game_apk_required)
					setMessage(R.string.game_apk_message)
					setPositiveButton(R.string.game_apk_install) { _, _ ->
						val intent = Intent(Intent.ACTION_VIEW,
							getDownloadPageForGameDir(basedir.name).toUri())
						ctx.startActivity(intent)
					}
					show()
				}
				return
			}

			launchEngine(ctx, commandLineArgs, packageName = packageName, gameLibDir = gameLibDir)
			return
		}

		if (packageNames == null) {
			// Unknown game — try to use downloaded libraries from hlsdk-mega-build
			val downloader = GameLibDownloader(ctx)
			val args = commandLineArgs

			if (downloader.isDownloaded(basedir.name)) {
				downloader.logExistingLibs(basedir.name)
				launchEngine(ctx, args)
				return
			}

			val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
			scope.launch {
				when (val r = downloader.lookupBuild(basedir.name)) {
					is GameLibDownloader.Lookup.Available -> showDownloadDialog(ctx, downloader, args)
					is GameLibDownloader.Lookup.NotInManifest -> launchEngine(ctx, args)
					is GameLibDownloader.Lookup.Error -> showManifestErrorDialog(ctx, args, r.cause)
				}
			}
			return
		}

		launchEngine(ctx, commandLineArgs)
	}

	private fun showDownloadDialog(ctx: Context, downloader: GameLibDownloader, commandLineArgs: String) {
		showDownloadProgressDialog(
			ctx = ctx,
			titleRes = R.string.downloading_game_libs,
			cancelable = true,
			scope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
			download = { onProgress -> downloader.download(basedir.name, onProgress) },
			onSuccess = { launchEngine(ctx, commandLineArgs) },
		)
	}

	private fun showManifestErrorDialog(ctx: Context, commandLineArgs: String, cause: Throwable) {
		MaterialAlertDialogBuilder(ctx)
			.setTitle(R.string.manifest_error_title)
			.setMessage(ctx.getString(R.string.manifest_error_message, cause.message ?: cause.javaClass.simpleName))
			.setPositiveButton(R.string.launch_anyway) { _, _ -> launchEngine(ctx, commandLineArgs) }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun launchEngine(
		ctx: Context,
		commandLineArgs: String,
		packageName: String? = null,
		gameLibDir: String? = null
	) {
		ctx.startActivity(Intent(ctx, XashActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

			putExtra("gamedir", defaultGameDir)
			putExtra("argv", commandLineArgs)
			putExtra("usevolume", pref.getBoolean("use_volume_buttons", false))
			putExtra("basedir", basedir.parent)

			if (gameLibDir != null) putExtra("gamelibdir", gameLibDir)
			if (packageName != null) putExtra("package", packageName)
		})
	}

	private fun parseGameInfo(file: File) {
		FileInputStream(file).use { inputStream ->
			inputStream.bufferedReader().use { reader ->
				reader.forEachLine {
					val tokens = it.split("\\s+".toRegex(), limit = 2)
					if (tokens.size >= 2) {
						val k = tokens[0]
						val v = tokens[1].trim('"')

						if (k == "title" || k == "game") title = v
						if (k == "icon") iconName = v
					}
				}
			}
		}
	}

	private fun getPackageNamesForGameDir(gamedir: String): Array<String>? {
		if (gamedir.equals("cstrike", ignoreCase = true)
			|| gamedir.equals("czero", ignoreCase = true))
			return arrayOf("su.xash.cs16client.test", "su.xash.cs16client")

		if (gamedir.equals("tfc", ignoreCase = true))
			return arrayOf("su.xash.tf15client.test", "su.xash.tf15client")

		// mobile_hacks hlsdk-portable branch allows us to have few more mods out of the box
		if (mobileHacksGames.any { it.equals(gamedir, ignoreCase = true) })
			return arrayOf("su.xash.engine")

		return null
	}

	private fun getDownloadPageForGameDir(gamedir: String): String {
		if (gamedir.equals("cstrike", ignoreCase = true)
			|| gamedir.equals("czero", ignoreCase = true))
			return "https://github.com/Velaron/cs16-client/releases/download/continuous/CS16Client-Android.apk"

		if (gamedir.equals("tfc", ignoreCase = true))
			return "https://github.com/Velaron/tf15-client/releases/download/continuous/TF15Client-Android.apk"

		// just so we don't return null
		return "https://github.com/SKIFE-BRADAR/xash3d-fwgs-android/releases/download/continuous/xash3d-fwgs-android.apk"
	}

	private fun getGameLibDir(ctx: Context, packageName: String): String? {
		val packageInfo: PackageInfo = ctx.packageManager.getPackageInfo(packageName, 0)
		return packageInfo.applicationInfo?.nativeLibraryDir
	}

	companion object {
		private const val TAG = "Game"

		private val customResolutionPattern =
			Regex("""^\s*(\d{2,5})\s*[xX×]\s*(\d{2,5})\s*$""")

		/**
		 * Parses a user supplied exact scene resolution such as "860x605" or
		 * "1920 x 1080". Returns null when the text is empty or out of range.
		 */
		fun parseCustomResolution(value: String?): Pair<Int, Int>? {
			val match = customResolutionPattern.matchEntire(value?.trim().orEmpty()) ?: return null
			val width = match.groupValues[1].toIntOrNull() ?: return null
			val height = match.groupValues[2].toIntOrNull() ?: return null

			if (width !in 64..8192 || height !in 64..8192)
				return null

			return width to height
		}

		suspend fun getGames(ctx: Context, root: File, previous: List<Game> = emptyList()): List<Game> {
			val known = previous.associateBy { it.basedir }
			val games = mutableListOf<Game>()

			val entries = root.listFiles() ?: throw IOException("Cannot list $root")

			for (dir in entries) {
				currentCoroutineContext().ensureActive()

				try {
					if (!dir.isDirectory) continue
					val gameInfoFile = checkIfGamedir(dir) ?: continue

					games.add(Game(ctx, dir, gameInfoFile, known[dir]))
				} catch (e: Exception) {
					Log.w(TAG, "Skipping game directory $dir", e)
				}
			}

			return games
		}

		fun checkIfGamedir(gamedir: File): File? {
			gamedir.listFiles()?.forEach {
				if (it.isFile) {
					if (it.name.equals("liblist.gam", ignoreCase = true))
						return it

					if (it.name.equals("gameinfo.txt", ignoreCase = true))
						return it
				}
			}

			return null
		}
	}
}
