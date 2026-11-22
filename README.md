# Abyss

An unofficial fan-made music player. Not affiliated with Juice WRLD, his estate or his labels.

- `index.html` + `version.json`: the live player. Installed copies of Abyss check these and update themselves.
- `app/`: the desktop shell (Electron). Build with `npm i && npx electron-builder --win --x64`.

To ship an update: change `index.html`, raise the `abyss-version` number in it, and set the same number in `version.json`.
