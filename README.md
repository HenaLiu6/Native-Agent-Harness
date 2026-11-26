# Pocket Runtime

Pocket Runtime is an Android app manager and runtime for small, browser-compatible web apps. Create and run multiple projects on your phone, edit them with a Token Harbor-powered chat agent, and transfer projects with a PC over local Wi-Fi.

**Demo:** Bilibili video link coming soon.  
![App screenshots — assets to be added](assets/screenshots.png)

## Download

When an APK is published, download it from the project's Releases page. To build and install from Windows PowerShell, install JDK 17, Gradle 8.7+, and Android SDK Platform 35 with Platform Tools, then run these commands from the repository root:

```powershell
$env:JAVA_HOME = "C:\Path\To\JDK17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
gradle assembleDebug

$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb devices
& $adb install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

`install -r` updates the app without uninstalling it, so its private project data is retained. Do not uninstall or clear app data if you want to keep projects. To inspect the registered projects on a connected device:

```powershell
& $adb shell run-as com.example.pocketruntime cat files/root/metadata/apps.json
```

This last command is optional; it reads the app's private registry and is not required to build or install. The debug APK is for testing, not public distribution.

## Use

Create a project with **Add App**; tap its card to run it or long-press to open its agent chat. Configure your Token Harbor key and model in **Settings**. For PC transfer, connect both devices to the same trusted Wi-Fi network, open **Connect to PC**, then follow the address and pairing-code instructions.

## Runtime and npm packages

The Android host is Kotlin; individual projects are ordinary HTML, CSS, and browser JavaScript files and do not need Gradle or a desktop build step. App files, the shared npm package cache, and runtime metadata live separately in private app storage:

```text
root/
  apps/<app-name>/       Project files, including optional packages.json
  packages/<name>/<version>/  Shared extracted npm packages
  metadata/              App registry and runtime data
  agent/                 Per-app chat histories
```

Declare browser-compatible npm dependencies in each app's `packages.json` using exact versions:

```json
{
  "dependencies": {
    "comfy.js": "1.1.29"
  }
}
```

When an app launches or `test_app` runs, Pocket Runtime checks these entries. Missing packages are fetched from the npm registry, integrity-checked, and extracted into the shared cache; an already cached version is reused. The app can also install a package through the agent's `install_package` tool, which adds it to that app's manifest. Package lifecycle scripts are never run.

Apps use native browser modules, for example `<script type="module" src="./app.js"></script>`. The host serves project files through AndroidX `WebViewAssetLoader` at `https://appassets.androidplatform.net/`, disables WebView file access, and adds an import map for declared packages. Package entry points are selected from metadata such as `browser`, `module`, `main`, or supported `exports` fields.

This is not the full npm/Node.js toolchain: there is no Node.js, npm CLI, bundler, or per-app `node_modules`. Current manifests require exact `x.y.z` versions and support unscoped package names; dependencies must be declared directly in the app manifest rather than relying on automatic transitive dependency installation. Use packages that provide browser-compatible code—packages needing Node.js APIs or native modules will not run in WebView.

Projects have separate directories, and the agent's file tools are confined to the selected project's directory. The package cache is intentionally shared between apps, is not writable through those project file tools, and should not be treated as a separate per-app security boundary.
