# Abyss

An unofficial fan-made music player. Not affiliated with Juice WRLD, his estate or his labels.

## Download (Windows)

**[Download Abyss-Windows.7z](https://github.com/slayerischillin/abyss/raw/main/download/Abyss-Windows.7z)** (74 MB)

1. Right-click the file and choose **Extract All** (Windows 11), or open it with 7-Zip.
2. Open the **Abyss** folder and run **Abyss.exe**.
3. If Windows says "Windows protected your PC", click **More info**, then **Run anyway**.

Abyss updates itself after that.

## What's in this repo

- `index.html` + `version.json`: the live player. Installed copies of Abyss check these and update themselves.
- `app/`: the desktop shell (Electron). Build with `npm i && npx electron-builder --win --x64`.

To ship an update: change `index.html`, raise the `abyss-version` number in it, and set the same number in `version.json`.
