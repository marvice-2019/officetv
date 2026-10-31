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

## Extra app on the TV: AirPlay Server (iPhone, iPad, Mac mirroring)

The TV has no AirPlay of its own. `io.github.jqssun.airplay` (AirPlay Server 0.0.36, GPL-3.0, built on UxPlay)
was installed from F-Droid on 31 October 2026 at the owner's request: free, no anti-features listed on F-Droid,
APK SHA-256 `b8c7c595ed93f91562feedce92c27800f08f9c84df424f6ac2045e82324cf2a4`, signed by the developer
(`OU=jqssun`). Its server name is set to "Office", it starts at boot and runs in the background.
Tested: iPhone Screen Mirroring to "Office" works, about 1 s lag. Mac not yet tested.
Its optional PIN is off by the owner's choice (31 October 2026): anyone on the office Wi-Fi can mirror to the TV.

Remove it:

```bash
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm uninstall io.github.jqssun.airplay'
```

## Mirroring on this TV (tested 31 October 2026)

| Way | Result |
|---|---|
| HDMI cable on HDMI 1 | Works, no lag. The TV reports every HDMI port as connected, so Present cannot detect the cable; it opens the room screen and the cable card switches to HDMI 1. |
| Windows Win + K | Works only while HaiCast is open (the Mirror card's Windows step opens it); pick "Office". |
| Chrome Cast screen | Works from Windows and Mac, about 1 s lag. |
| Remote Home on an HDMI input | Returns to the office Home (checked with the real remote). |
| iPhone AirPlay | Works through AirPlay Server, about 1 s lag. A VPN on the iPhone hides "Office" from the Screen Mirroring list; turn it off, or allow LAN access in the VPN. |
