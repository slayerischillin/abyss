# Abyss

An unofficial fan-made music player. Not affiliated with Juice WRLD, his estate or his labels.

## Download (Windows)

**[Download Abyss-Windows.7z](https://github.com/slayerischillin/abyss/raw/main/download/Abyss-Windows.7z)** (74 MB)

1. Right-click the file and choose **Extract All** (Windows 11), or open it with 7-Zip.
2. Open the **Abyss** folder and run **Abyss.exe**.
3. If Windows says "Windows protected your PC", click **More info**, then **Run anyway**. **(NOT A VIRUS)**

Abyss updates itself after that.

## Download (Android phones and tablets)

**[Download Abyss.apk](https://github.com/slayerischillin/abyss/releases/download/android/Abyss.apk)**

1. Open the link on your phone or tablet and download **Abyss.apk**.
2. Open the file. If Android asks, allow installing apps from your browser or file manager.
3. Tap **Install**.

## What's in this repo

- `index.html` + `version.json`: the live player. Installed copies of Abyss check these and update themselves.
- `app/`: the desktop shell (Electron). Build with `npm i && npx electron-builder --win --x64`.

Patch notes live in `changelog.json` and show up in the app when it updates.

To ship an update: change `index.html`, raise the `abyss-version` number in it, and set the same number in `version.json`.
