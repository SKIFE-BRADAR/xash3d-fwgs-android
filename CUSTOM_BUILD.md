# Custom Android build

This repository is a small fork of [FWGS/xash3d-fwgs](https://github.com/FWGS/xash3d-fwgs)
(based on commit `707596f`) with changes focused on running the engine on
Android 17 / One UI 9 and on exposing useful video settings from the Android
launcher.

## Changes

### Android 17 (API 37) startup crash fix

The new engine defaults to the NanoGL/GLES1 renderer on Android. Android 17
broke the legacy GLES1 driver path, so renderer initialization crashed in
`nanoWrap.cpp` at `glEsImpl->glGetIntegerv()` (called from
`GL_InitExtensions()`).

* `gles1` (NanoGL) is no longer selected automatically on Android API 37+.
  An explicit `-ref gles1` (or the launcher's renderer setting) still works,
  for debugging/legacy devices.
* A stale `r_refdll gles1` entry in `video.cfg` is ignored on API 37+ too.
* `GL_InitExtensions()` now parses the GL version string first and never calls
  the GLES3-only `GL_MAJOR_VERSION`, `GL_MINOR_VERSION` and
  `GL_NUM_EXTENSIONS` enums on older contexts.
* `glConfig.extensions_string` is never left `NULL`, so extension checks cannot
  crash on drivers that return no extension string.

### Video settings

* New engine argument: `-msaa <0|2|4|8|16>`. It is applied after the renderer
  config is executed but before the GL context is created, so unlike
  `+gl_msaa_samples` it actually takes effect.
* New engine arguments: `-scene_width <px>`, `-scene_height <px>` and
  `-scene_scale <0.1-8.0>`. They are parsed after the renderer has loaded and
  executed `opengl.cfg`, so they reliably override saved values. `0 0` clears
  an exact scene size. These exist because `+r_scene_*` commands only run
  through `stuffcmds`, which requires the game's `.rc` file and therefore may
  silently do nothing on Android.
* The renderer always prints the effective scene resolution to the console and
  `engine.log` (for example `Scene resolution: rendering 3D at 860x605 (0x
  MSAA), upscaling to 2400x1080`), so the setting can be verified.
* When a non-native scene resolution is requested, the launcher prefers GL4ES
  over NanoGL/GLES1 (which compile out render target support) and passes
  `-scene_info`. The renderer then shows the effective size as a center
  message and a top-left notification, and the launcher shows a startup toast
  with the app version, requested size and renderer.
* The Android launcher now has per-game settings for:
  * render resolution (`Native`, `1080p`, `720p`, `540p`, `480p`, `360p`),
    implemented with the renderer's `r_scene_scale`;
  * custom exact resolution (for example `860x605`), implemented with the
    renderer's `r_scene_width` and `r_scene_height`. When both are set they
    override `r_scene_scale`; the HUD is still drawn at native resolution;
  * MSAA (off, 2x, 4x, 8x, 16x);
  * renderer (`Automatic`, `GL4ES`, `GLES3`, `GLES1`, `Software`).

### OpenGL error console spam

Some GL wrappers report the same harmless error every frame. The renderer now
prints each unique error and call site once, caps repeated texture-upload
errors, and suppresses duplicate GL debug callbacks instead of flooding the
console every frame.

## Building

The `Android custom build` GitHub Actions workflow builds an arm64-v8a APK
and publishes it to the `continuous` prerelease:

```sh
bash scripts/gha/deps_android.sh
bash scripts/gha/build_android.sh
```

The resulting APK is written to `artifacts/xash3d-fwgs-android.apk`.

## License

The engine is licensed under the GNU GPLv3. See `LICENSE` and the upstream
repository for details.
