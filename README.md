# Image Cleanup Assistant

A simpler Windows desktop cleanup tool focused on removing unwanted images from local storage.

## What it does

- Scans a local folder recursively for supported image files.
- Creates cleanup queues for `Exact Duplicates`, `Similar Photos`, `Blurry Photos`, `Likely Forwards`, `Screenshots`, and `Text-Heavy Images`.
- Uses a simpler three-pane review flow: queue list on the left, current queue in the middle, and a large preview workspace on the right.
- Lets you compare duplicate pairs side by side and delete the left image, right image, or both.
- Flags very soft photos using a simple blur heuristic based on sharpness and edge detail.
- Flags likely WhatsApp forwards such as greeting cards, quote cards, good-morning images, and similar shareable graphics.
- Flags likely screenshots and text-heavy graphics such as quote cards, posters, and greetings.
- Sends deleted files to the Recycle Bin instead of permanently removing them.
- In `Blurry Photos`, `Likely Forwards`, `Screenshots`, and `Text-Heavy Images`, pressing `Delete` removes the selected image directly to the Recycle Bin without a confirmation prompt.
- `K` keeps the current item, `N` moves to the next item, and `Enter` opens a larger preview/compare window.

## Android app

- Latest APK: [`ImageCleanupAssistant-android-debug.apk`](C:\Users\saura\Documents\Playground\ImageCleanupAssistant-android-debug.apk)
- The Android app now includes an optional on-device `AI review` flow for `Likely Forwards`.
- The APK does not bundle the model. Import a downloaded `.litertlm` model file from inside the app.
- Recommended model: `Gemma 3n E2B LiteRT-LM preview`
- Model page: [Gemma 3n E2B LiteRT-LM preview](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm-preview)

## Run it

Double-click [`Run-ImageCleanupAssistant.bat`](C:\Users\saura\Documents\Playground\Run-ImageCleanupAssistant.bat), or run:

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
