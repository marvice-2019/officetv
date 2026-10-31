# Office TV

The shared office TV for Marvice Media Private Limited and Infyst Global Private Limited
(Haier MatrixTV BE, Google TV 14, Bengaluru).

| Path | What it is |
|---|---|
| `examples/haier-matrixtv-be-india/` | Part 1: the debloat record for this TV (what was disabled, what was kept and why) |
| `launcher-office/` | Part 2: the office launcher, app ID `in.marvice.officetv`, forked from CleanYourTV's `launcher/` |
| `tools/` | CleanYourTV's ADB tools, which `launcher-office/build-and-install.sh` calls |
| `LICENSE` | CleanYourTV's MIT licence, which covers the forked code |

Upstream: https://github.com/Gathin23/cleanyourtv (MIT).

## Build and install

Release-signed only. The key never lives in this repo: put `officetv-signing.properties`
(storeFile, storePassword, keyAlias, keyPassword) at `~/keystores/` or point `OFFICETV_SIGNING` at it.

```bash
cd launcher-office && TV_HOST=<tv ip> ./build-and-install.sh
```

## Undo the debloat

```bash
TV_HOST=<tv ip> tools/restore.sh examples/haier-matrixtv-be-india/debloat.txt com.google.android.apps.tv.launcherx
```
