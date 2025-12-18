# Fiverr Auto Refresher (Android)

<p align="center">
  <img src="app_images/1.png" alt="Main controls screen" width="240"/>
  <img src="app_images/2.png" alt="Floating timer overlay" width="240"/>
</p>

**Fiverr Auto Refresher** is a lightweight, private Android automation app built using **Accessibility Services**.  
It keeps your Fiverr profile active by periodically performing a **pull-to-refresh gesture**—without reopening the app every time.

The app is designed for **manual, single-device use** and is **not published** on the Play Store.

---

## What you’re seeing above

- **Main screen (left):**
    - Set refresh interval
    - Start / Stop automation
    - One-time *Tap Test*
    - Quick links to Accessibility & Battery settings

- **Floating timer bubble (right):**
    - Appears while automation is running
    - Shows live countdown
    - Draggable (snaps to screen edges)
    - Tap to reopen the app instantly

---

## How it works (simple explanation)

1. Opens Fiverr only if it is **not already foreground**
2. Waits briefly for the UI to load
3. Performs a **top → bottom swipe gesture** (pull-to-refresh)
4. Waits for the selected interval
5. Repeats until you stop it

This avoids unnecessary app relaunches and reduces delays.

---

## Why it’s useful

- **Keeps you “online”**: periodic refreshes help maintain Fiverr presence
- **Hands-free**: set interval once, let it run
- **Private & local**: no servers, no APIs, no scraping
- **Safe by design**: no root, no injections—Accessibility gestures only
- **Battery-aware**: optional battery-optimization whitelist
- **Foreground-smart**: won’t relaunch Fiverr if it’s already open

---

## Quick start (APK install)

1. Copy  
   `app/build/outputs/apk/release/app-release.apk`  
   to your phone
2. Allow *Install from unknown sources* for your file manager
3. If Play Protect warns, choose **Install anyway**
4. Install the APK

---

## First-time setup

1. **Enable Accessibility Service**
    - Open the app
    - Tap **Accessibility Settings**
    - Enable **Fiverr Auto Refresher**
    - On Android 13+ sideloads: if the toggle is disabled, open **App info → (⋮) → Allow restricted settings**, then return and enable it.

2. **Disable battery optimization (recommended)**
    - Tap **Battery Optimization** in the app
    - Or whitelist it manually in system battery settings

3. *(Optional)* Pin the app in Recents (OEM-dependent) to reduce background kills

---

## Using the app

- **Interval (minutes):** set between `1–60`
- **Start Automation:** begins the refresh loop
- **Stop Automation:** stops immediately
- **Tap Test:** runs a single open + refresh to verify setup
- **Go to Fiverr Home before refresh (optional):** when ON, the app taps the Home tab (bottom-left) before each swipe; when OFF, it refreshes the current Fiverr screen (e.g., chats/gigs).
- **Floating Timer (optional):**
    - Enable it
    - Grant *Draw over other apps*
    - Start automation to see live countdown

If Accessibility is disabled, the app will redirect you to settings automatically.

---

## Notes & tips

- After reboot, re-enable the Accessibility Service
- On Android 13+ sideloads you must allow **restricted settings** (see above) before enabling Accessibility
- If Fiverr is already open, the app only refreshes—no relaunch
- Play Protect warnings are normal for accessibility automation tools
- ADB install (optional):
  ```
  adb install -r app/build/outputs/apk/release/app-release.apk
  ```

---

## Building from source

- **Debug APK**
  ```
  GRADLE_USER_HOME=.gradle ./gradlew assembleDebug
  ```

- **Release APK (signed & shrunk)**
  ```
  GRADLE_USER_HOME=.gradle ./gradlew assembleRelease
  ```

---

## Permissions used

- **Accessibility Service** – required for swipe/tap gestures
- **Draw over other apps** – only for floating timer bubble
- **Ignore battery optimizations** – optional, improves stability

---

### ⚠️ Disclaimer
This app is for **personal, manual use only** on your own device.  
It is not affiliated with or endorsed by Fiverr.
