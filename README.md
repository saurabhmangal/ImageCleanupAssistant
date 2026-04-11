# Image Cleanup Assistant

A simpler Windows desktop cleanup tool focused on removing unwanted images from local storage.

## What it does

- Scans a local folder recursively for supported image files.
- Creates cleanup queues for `Exact Duplicates`, `Near Duplicates`, `Low-Quality Shots`, `Messaging Clutter`, `Screenshots`, and `Documents`.
- Uses a simpler three-pane review flow: queue list on the left, current queue in the middle, and a large preview workspace on the right.
- Lets you compare duplicate pairs side by side and delete the left image, right image, or both.
- Flags very soft photos using a simple blur heuristic based on sharpness and edge detail.
- Flags likely messaging clutter such as WhatsApp forwards, memes, stickers, greeting cards, and quote images.
- Flags likely screenshots and document-style images such as receipts, IDs, forms, tickets, and scans.
- Sends deleted files to the Recycle Bin instead of permanently removing them.
- In `Low-Quality Shots`, `Messaging Clutter`, `Screenshots`, and `Documents`, pressing `Delete` removes the selected image directly to the Recycle Bin without a confirmation prompt.
- `K` keeps the current item, `N` moves to the next item, and `Enter` opens a larger preview/compare window.

## Android app

- Latest APK: [`ImageCleanupAssistant-android-debug.apk`](C:\Users\saura\Documents\Playground\image-cleanup-app\ImageCleanupAssistant-android-debug.apk)
- The Android app can now expose a same-Wi-Fi browser dashboard. Start `Wi-Fi Dashboard` inside the app, then open the shown `http://<phone-ip>:9864` address from your laptop or another phone on the same network.
- The browser dashboard is inspired by the `duplicate_video_app` flow: one premium-looking review page with scan controls, queue tabs, image cards, and direct delete actions.
- The remote dashboard reuses the on-device queues for `Exact Duplicates`, `Near Duplicates`, `Low-Quality Shots`, `Messaging Clutter`, `Screenshots`, and `Documents`.
- For browser-driven delete, grant Android `All files access` from the new `Grant Delete Access` button in the app. Without that permission, remote review still works but delete stays disabled.
- The Android app now includes an optional on-device `AI review` flow for messaging clutter and forward-style images.
- The APK does not bundle the model. Import a downloaded `.litertlm` model file from inside the app.
- Recommended model: `Gemma 3n E2B LiteRT-LM preview`
- Model page: [Gemma 3n E2B LiteRT-LM preview](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm-preview)

## Run it

Double-click [`Run-ImageCleanupAssistant.bat`](C:\Users\saura\Documents\Playground\image-cleanup-app\Run-ImageCleanupAssistant.bat), or run:

```powershell
powershell.exe -ExecutionPolicy Bypass -Sta -File .\ImageCleanupAssistant.ps1
```

## Optional test mode

You can run a non-UI scan to validate a library folder:

```powershell
powershell.exe -ExecutionPolicy Bypass -Sta -File .\ImageCleanupAssistant.ps1 -InitialLibraryPath "C:\Path\To\Photos" -SelfTest
```

## Notes

- Supported formats: `.jpg`, `.jpeg`, `.png`, `.bmp`, `.gif`, `.tif`, `.tiff`
- Similar-photo, blur, and forward detection are heuristic, so review the preview before deleting.
- Unreadable image files are skipped instead of crashing the app.

## Behind the Scenes

Check out this video for a behind-the-scenes look at the application development process:

[Image Cleanup Assistant - Development Journey](https://youtu.be/wogtY7yj4sc)
