# OpenDash

A dead-simple, open-source Material 3 Android app for Wi-Fi dashcams — built because Chinese dashcam companion apps are a crime against software engineering.

## Why OpenDash Exists

If you've ever bought a dashcam and been forced to install Viidure, Roadcam, Timacam, or any of their reskinned cousins, you already know the pain:
- **300+ MB of bloated garbage** just to pull a video off an SD card over local Wi-Fi.
- **Packed with ad SDKs, trackers, analytics, and sketchy closed-source native `.so` blobs** phoning home for no reason.
- **Forced accounts, broken UIs, and crippled downloads** that choke on Android's Wi-Fi/cellular routing (`Network` binding) or dump unplayable `.ts` files onto your phone.

OpenDash throws all of that in the trash:
- **Zero telemetry, zero ads, zero accounts, zero native `.so` binaries.**
- **Material 3 with Dynamic Color** — fast, clean, and strictly two tabs: **Album** and **Settings**.
- **Wi-Fi `Network`-bound HTTP/TCP** so Android never routes `192.168.x.x` dashcam requests over 5G or a VPN.
- **Resilient downloads with automatic `206 Partial Content` resume** and `WakeLock`/`WifiLock` support.
- **Lossless `.ts` → `.mp4` remuxing** via Android `MediaExtractor` + `MediaMuxer` (stripping ADTS headers so Google Photos, Google Files, and Gallery apps play them natively).

## Supported Dashcam Protocols

- Novatek (`192.168.1.254`)
- MStar / SigmaStar (`192.72.1.1`)
- Hisilicon (`192.168.0.1`)
- Jieli (`192.168.1.1:8080`)
- Nello (`192.168.1.1:10080`)
- Allwinner (`192.168.10.1:8082`)
- Generalplus (`192.168.25.1:8080` / `8081`)
- iCatchTek (`192.168.1.1:15740`)
- Huiying (`192.168.201.1`)
- Eeasy (`192.168.169.1`)
- TUTK / W18 (`192.168.10.1:8082`)

## Build

```bash
./gradlew assembleDebug testDebugUnitTest
```
