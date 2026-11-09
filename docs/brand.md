# Wisp brand

Wisp is small, quiet and private, so it looks that way: one soft line on a deep, calm background.

## The ribbon motif
One flowing stroke, in the accent colour, with round ends. It shows up in four places and always means "your input in motion":

| Where | What it is |
|---|---|
| App icon | three overlapping lines of one stroke, the newest brightest (`res/drawable/ic_launcher_foreground.xml`; a single-line `ic_launcher_monochrome.xml` for themed icons) |
| Swipe trail | the path your finger draws, fading behind it (`KeyboardView.drawTrail`) |
| Voice panel | the level ribbon that moves with your voice (`VoicePanelView`), three strokes of falling opacity (230, 120, 60) |
| Settings | the accent colour on titles and the active controls |

## Tokens (all in `style/Brand.kt`)
- **Plum** `#2A2230` is the dark background; keys are lighter plums. **Ink** `#241A1E` is the text on light surfaces.
- **Coral** `#FF7A66` is the default accent. Six others ship (rose, violet, blue, teal, green, amber) and "dynamic" follows the wallpaper on Android 12+.
- On a light background an accent is darkened until it can be read as text (3:1) and its label reads on it (4.5:1) (`Brand.accentOn`).
- **Type:** the system font only (no font files to ship or load). Key letters 22 sp, key labels 15 sp, suggestion strip 17 sp. In the settings app, titles are semibold and body text is regular; sizes follow the phone's font-size setting.
- **Shape:** keys have 9 dp corners by default (square 3, pill 18). Cards in settings use Material 3's medium shape.

## Checklist (each item is checked by a test or a look at the screenshots)
1. The keyboard and the settings app take every colour from `Brand` (grep: no hex colours outside `style/Brand.kt`, `WispTheme.kt` and the icon files).
2. Key text on keys, and text on the background, reach 7:1 in both themes; muted labels reach 4.5:1 (`BrandTest`).
3. The enter key's label reads on every accent in both themes (`BrandTest`).
4. Every accent is distinguishable from the background it sits on, in both themes (`BrandTest`).
5. The swipe trail, voice ribbon and icon are the same stroke style: round caps, no outline, no shadow.
6. Nothing in the app, its strings or its icon carries another product's name or logo (credits are in `THIRD_PARTY.md` and the About screen).
7. The adaptive icon keeps its artwork inside the 66 dp safe zone and has a monochrome version.
