# Phone QA checklist

Work through this on a real phone with a release build (`./gradlew assembleRelease`, install `app-arm64-v8a-release.apk`). Tick a box when it behaves as described. For anything that does not, write down: the item number, what you did, what happened, the app you were typing in, and the phone model. Anything marked **(!)** is a release blocker.

Use at least two different apps for the typing sections (a messaging app and a browser or notes app), and repeat the core typing items once in a password field.

## 1. Install and first run
- [ ] 1.1 The APK installs over a previous build without errors.
- [ ] 1.2 The Wisp app opens to the Welcome screen the first time; the three steps are listed.
- [ ] 1.3 "Open keyboard list" takes you to Android's keyboard settings; after you switch Wisp on, step 1 ticks itself when you come back.
- [ ] 1.4 Step 2 (choose Wisp as your keyboard) opens the picker; after choosing, it ticks itself.
- [ ] 1.5 "Skip for now" works and lands on Home. The Welcome screen does not come back on the next launch.
- [ ] 1.6 **(!)** Settings > Apps > Wisp > Permissions lists only what is documented: Microphone (asked for when you first use voice), plus Internet, notifications and installing updates for the optional model downloader. Settings > Data usage shows no network use while you only type and dictate.
- [ ] 1.7 Home shows "Wisp is your keyboard" (green dot), the version, "0 KB sent off this phone", and a Test area.
- [ ] 1.8 The home-screen icon is the Wisp ribbon icon (and a single-colour version appears with themed icons on Android 13+).

## 2. Basic typing
- [ ] 2.1 **(!)** Tap through the whole alphabet, digits and punctuation: every key types the right character.
- [ ] 2.2 Typing fast with two thumbs: no missed or doubled keys, no visible lag.
- [ ] 2.3 **(!)** Shift once capitalises one letter; double-tap shift is caps lock; tapping shift again turns it off. The key labels show it in every theme: small letters when shift is off, capitals when it is on.
- [ ] 2.3b **(!)** The shift icon tells the three states apart: an outline arrow (off), a filled arrow (one letter), a filled arrow with a bar under it (caps lock).
- [ ] 2.4 After ". " or "? " the next letter is capitalised automatically; at the start of a field too.
- [ ] 2.5 Double space gives ". " (after a word).
- [ ] 2.6 Long-press a letter (e, a, o, u, c, n): the popup lists accents; sliding to one and releasing types it; releasing without sliding types the first extra.
- [ ] 2.7 Long-pressed hint characters (the small glyph on each key) match what the popup gives first.
- [ ] 2.8 Enter key: new line in a notes app; the right action (Send, Search, Go, Done) shows on the key in apps that set one, and does that action.
- [ ] 2.9 **(!)** The key under your finger lights up on touch with the same rounded shape as the key (no square box around it), and the highlight goes away when you slide off it. Check a few themes (Wisp, Factory, Retro, Comic, Cyberpunk).
- [ ] 2.10 Haptic feedback is felt on key press (Settings, Feedback: off / system / custom; custom has a strength slider you can feel change).
- [ ] 2.11 Key sound: Paper, Thock and Wood are clicks (space, enter and backspace sound lower); Kalimba and Bells play the next note of a pleasant melody per key (space a soft chord, backspace a step down). In settings, each tap on a sound and each move of the volume plays a sample; clean at 100%, clearly louder at 300%. Silent in password fields and on a silent phone.

## 3. Space bar and bottom row
- [ ] 3.1 **(!)** The space bar is wide (roughly 40% of the row) and easy to hit with either thumb.
- [ ] 3.2 Bottom row reads: ?123, comma, space, period, enter. There is no emoji key; emoji opens from the toolbar. Holding the space bar only slides the cursor.
- [ ] 3.3 Tapping the right and left ends of the space bar both insert a space.
- [ ] 3.4 Sliding a finger left and right along the space bar moves the cursor; it follows the finger and stops cleanly when you lift.
- [ ] 3.5 Long-press the comma key: the globe (switch keyboard), the language and extra punctuation appear. Long-press the period key: the punctuation list appears.
- [ ] 3.6 The globe key: a tap goes to your previous keyboard; a long press opens Android's keyboard picker. Wisp can always be reached again from the picker.
- [ ] 3.7 The EN key switches English, Hinglish in turn, and the key label changes.

## 4. Backspace and deleting
- [ ] 4.1 A tap deletes one character.
- [ ] 4.2 **(!)** Hold backspace: it repeats, speeds up, then goes by whole words.
- [ ] 4.3 **(!)** Hold backspace and slide left: letters are selected one at a time as you move, one more for each small step of the finger.
- [ ] 4.4 Sliding back to the right shrinks the selection; sliding back to where you started and lifting deletes nothing.
- [ ] 4.5 Lifting after selecting some letters deletes exactly the selected letters.
- [ ] 4.6 Backspace swipe works across emoji (an emoji counts as one character, not two).
- [ ] 4.7 Backspace swipe works at the start of text (stops cleanly, no crash) and in the middle of a line.
- [ ] 4.8 With "Swipe to delete" off in Home, sliding left from backspace does nothing special; turning it back on restores it.
- [ ] 4.9 Selected text is deleted by backspace as usual (select a word with the app's handles, then backspace).

## 5. Suggestions and autocorrect
- [ ] 5.1 While typing a word, three suggestions show in the strip; the middle one is the best match and bold.
- [ ] 5.2 Tapping a suggestion replaces the word and adds a space.
- [ ] 5.3 **(!)** "teh " becomes "the ", and one backspace right after puts "teh" back. It is not corrected again after that.
- [ ] 5.4 A correct word ("hello") is never changed. Names and unknown words are left alone.
- [ ] 5.5 Typing a new word, then tapping the "keep as typed" entry on the left of the strip, teaches it; it is offered next time.
- [ ] 5.6 After a space, next-word suggestions appear ("how", then "are", "you" ...).
- [ ] 5.7 Autocorrect off in Home: nothing is replaced automatically, suggestions still show.
- [ ] 5.8 With the field empty, the strip shows the toolbar icons (apps, clipboard, emoji, rewrite, crop, palette, settings) and the mic on the right.
- [ ] 5.8b Tap at the end of a word that is already in a text box (or open a box that has text): that word shows in the strip with corrections, and tapping one replaces it without an extra space. Tapping in the middle of a word offers nothing.
- [ ] 5.9 Password fields: no suggestions, nothing learned, mic does nothing.
- [ ] 5.10 Text shortcuts (Settings > Typing): add "omw" as "on my way"; typing "omw " expands it. Undo with backspace.

## 6. Glide (swipe) typing
- [ ] 6.1 **(!)** Swipe across h-e-l-l-o: "hello " is inserted with a trailing space. The trail follows the finger and the touched keys light up.
- [ ] 6.2 Swipe several common words in a row; each inserts with one space between them.
- [ ] 6.3 One backspace after a swipe removes the whole swiped word.
- [ ] 6.4 A long word (keyboard, international) swipes correctly.
- [ ] 6.5 Sloppy swipes (not exactly over each letter) still land the right word most of the time; if not, the right word is in the strip.
- [ ] 6.6 **(!)** A swipe always types its best word when it fits the path reasonably, even if another word is nearly as good (the others are in the strip to swap to; tapping one replaces the typed word). A swipe that is not a word at all inserts nothing and offers letters in the strip.
- [ ] 6.7 Swipe, delete it, then type the word by hand: the next swipes of that word improve over time (practice mode below speeds this up).
- [ ] 6.8 Settings > Typing: glide off stops swiping; the trail colour and thickness settings change the trail.
- [ ] 6.9 Practice swiping (Home tile): a session of words runs on the same keyboard you type with (your letter layout, number row and theme); "words glided this week" on Home goes up.
- [ ] 6.10 Swiping works in portrait, landscape, one-handed and split modes.

## 7. Layouts, number row and number pad
- [ ] 7.1 Settings > Layouts: switching between QWERTY, AZERTY, QWERTZ, Dvorak, Colemak and Workman changes the letter keys; typing, swiping and suggestions still work on each.
- [ ] 7.2 **(!)** Number row: turning it on from the Home chip puts 1 to 0 above the letters; it is also in Layouts/Size settings and in keyboard edit mode ("Number row" chip). Turning it off removes it. The keys stay a sensible size.
- [ ] 7.3 With the number row on, the top letter row's hints read % \ | = [ ] < > { } instead of digits (HeliBoard's); with it off they read 1 to 0. The other rows read @ # $ _ & - + ( ) and * " ' : ; ! ? either way.
- [ ] 7.4 **(!)** ?123 opens symbols page 1: digits row, then @ # $ _ & - + ( ) /, then * " ' : ; ! ?
- [ ] 7.5 "=\<" opens symbols page 2; tapping it again returns to page 1; ABC returns to letters.
- [ ] 7.6 **(!)** The "123" key on the symbols page opens a number pad with digits in the middle three columns (1 2 3 / 4 5 6 / 7 8 9 / , 0 .), operators + - * / down the left, and backspace, = , % and # down the right; the bottom row is ABC, globe, space, enter.
- [ ] 7.7 Number pad: every digit and symbol types; long-press an operator for its variants (for example * gives × and ·); ABC returns to letters.
- [ ] 7.8 Number-only fields (phone dialer, a field that asks for numbers) open the number pad directly, with a Done/Next key.
- [ ] 7.9 Email fields have an @ key where the comma sits; URL fields have a / key.
- [ ] 7.10 Editing a layout (Layouts and toolbar): a bad layout file is refused with a line and reason; the globe key can never be removed.
- [ ] 7.11 Toolbar editing: reorder or hide tools; the strip follows.

## 8. Emoji, clipboard
- [ ] 8.1 The smiley icon in the toolbar opens the emoji panel; categories scroll; tapping an emoji inserts it.
- [ ] 8.2 Emoji search finds results ("smile", "heart", "fire").
- [ ] 8.3 Kaomoji and Symbols tabs insert correctly. ABC returns to the letters; backspace works in the panel.
- [ ] 8.4 Recent emoji are remembered.
- [ ] 8.5 Clipboard icon opens history; copy something in another app and it appears. With Incognito on, the panel says copies are not kept.
- [ ] 8.6 Tapping a clip inserts it; pinning keeps it; filters (All, Pinned, Links, Codes) work.
- [ ] 8.7 **(!)** Copy a password from a password manager: it is not stored in history.
- [ ] 8.8 A copied one-time code appears under Codes and disappears sooner than other clips; unpinned clips clear after about an hour.
- [ ] 8.9 History on/off switch works; turning it off clears stored items.
- [ ] 8.10 Images copied from another app show as image items (or are skipped with a clear note).

## 9. Voice typing
- [ ] 9.1 Models screen: a speech model can be installed from a file you choose; the first-run advice names a model that fits the phone.
- [ ] 9.2 **(!)** Tap the mic once: the voice panel opens, asks for the microphone permission the first time, and starts listening straight away.
- [ ] 9.3 **(!)** Speaking shows the transcript live: confirmed words solid, the last few grey. The waveform moves with your voice.
- [ ] 9.4 **(!)** Tap the same mic button again (the big button in the panel, or the mic in the toolbar): listening stops and the text is inserted into the field. There is no pause button and no hold-to-talk.
- [ ] 9.5 While listening the big button shows a stop square; when idle it shows a mic.
- [ ] 9.6 X cancels and inserts nothing; the arrow at the top right inserts what was said and returns to the keyboard.
- [ ] 9.7 "Undo phrase" removes the last phrase; "Auto-punctuation" toggles punctuation.
- [ ] 9.8 After inserting, "Undo dictation" in the strip removes exactly what was inserted.
- [ ] 9.9 Speak while the model is still loading: the first words are not lost.
- [ ] 9.10 With no model installed, the mic explains and offers to open the model screen. Typing keeps working.
- [ ] 9.11 Close the keyboard and wait for the idle time: the voice process goes away.
- [ ] 9.12 Voice in a quiet room, a noisy room and with a Bluetooth headset: it records, and stops cleanly.
- [ ] 9.13 A phone call or another app taking the microphone during dictation does not crash Wisp.

## 10. Rewrite (AI)
- [ ] 10.1 **(!)** With no writing model installed, the rewrite panel explains and offers Open Models; tapping Open Models opens Wisp's Models screen (also when Wisp's settings are already open in the background) and the keyboard does not crash.
- [ ] 10.2 With a model installed: select a sentence, tap the sparkle icon, choose Polish; the result streams into the card with a shimmer first.
- [ ] 10.3 **(!)** The field is not changed until you press Replace. Closing the panel leaves the text untouched.
- [ ] 10.4 Friendly, Formal, Shorter, Bullets and Translate to Hindi each do what they say.
- [ ] 10.5 Replace swaps only the selected sentence; Insert below adds it on a new line; Regenerate gives another version.
- [ ] 10.6 "Polish" from the voice panel dictates, then opens the rewrite panel with the dictated text.
- [ ] 10.7 After the rewrite the model unloads when idle (memory returns).
- [ ] 10.8 32-bit phones only: the panel says rewrite is unavailable instead of failing.

## 11. Edit mode, size and position
- [ ] 11.1 The crop icon in the toolbar opens edit mode on the keyboard itself.
- [ ] 11.2 Dragging the keyboard up and down moves it; **(!)** the area below a raised keyboard is filled with the keyboard background down to the bottom of the screen, never a see-through gap. While you type the gap stays (the keys stay raised for your thumbs); when you open the emoji, clipboard or rewrite panel it grows into the gap for more room, and goes back when you return to the keys.
- [ ] 11.3 Resizing from the edges and corners changes width and height like cropping; keys rescale cleanly with no overlap.
- [ ] 11.4 Mode chips: Full, One-handed left, One-handed right, Floating, Split each work; Reset restores the default; Done keeps your choice.
- [ ] 11.5 One-handed: keys sit on one side; the swap button moves them to the other side; the widen button returns to full width. Taps on the empty strip type nothing.
- [ ] 11.6 Floating: drag the grip bar to move the keyboard anywhere; it keeps its place next time and never covers the field you are typing in unless you put it there.
- [ ] 11.7 Split: the two halves are separated by a gap; both halves type correctly, including the space bar.
- [ ] 11.8 The chosen position and size are remembered after closing and reopening the keyboard, after switching apps, and after a restart.
- [ ] 11.9 Portrait and landscape keep separate positions/sizes.
- [ ] 11.10 The app behind the keyboard scrolls so the field you are typing in stays visible when the keyboard is raised or resized.

## 12. Themes and the settings app
- [ ] 12.1 Settings > Themes: the theme cards fill the card edge to edge with no gap on the right; all thirty themes apply live to the preview and the keyboard (Wisp, Moon Phase, Dandelion, Sakura, Factory, Cyberpunk, Terminal, Y2K Desktop, Material You, Liquid Glass, Modern, Retro, Brutalist, Comic, Manga).
- [ ] 12.1b The strip above the keys, the keys and the gap or navigation bar below them read as one surface in every theme (patterns and photos continue across the strip).
- [ ] 12.2 Every theme is legible: key letters, hints, suggestions, toolbar icons, the mic button, the enter key.
- [ ] 12.3 Material You picks up wallpaper colours when "Colour from wallpaper" is on (Android 12+).
- [ ] 12.4 Liquid Glass shows a blurred, translucent keyboard (Android 12+) and falls back to plain translucent keys on older phones.
- [ ] 12.5 A custom photo background: pick a photo, the dimming slider keeps keys readable; Remove restores the theme.
- [ ] 12.6 Key borders and key hints toggles change the keys live.
- [ ] 12.7 **(!)** App appearance (top of the Themes screen): System, Light, Dark and Black each apply instantly; status and navigation bar icons stay readable in all four; the choice is kept after closing the app.
- [ ] 12.8 System mode follows the phone's dark mode switch without restarting the app.
- [ ] 12.9 Every Home tile and every settings section has a proper icon (no plain dots): Typing, Themes, Layouts, Models, Learned data, Backup, Practice swiping, Feedback, Position and size, Privacy.
- [ ] 12.10 Text sizes follow the phone's font-size setting; nothing is cut off at the largest size.
- [ ] 12.11 Home quick chips (Glide typing, Number row, Swipe to delete, Space cursor, Autocorrect, Haptics, Key sound) each flip their setting, and the keyboard follows the next time it is shown.

- [ ] 12.13 **(!)** Keyboard colours (Themes screen): System, Light and Dark each switch every theme that has both looks (Wisp, Moon Phase, Dandelion, Sakura, Material You, Liquid Glass, Modern, Retro, Brutalist, Comic, Manga) between its light and dark look, keeping the same theme selected. Factory, Cyberpunk, Terminal (dark only) and Y2K Desktop (light only) do not change.
- [ ] 12.14 **(!)** Space-bar art: Moon Phase, Dandelion, Sakura, Factory, Cyberpunk, Terminal, Y2K Desktop, Retro, Brutalist, Comic and Manga show their picture (several are static by design; motion is only ambient: drifting petals or seeds, sparks, twinkles, blinking cursors) on the space bar; the "Space-bar art" choice (Themes > Fine-tune: Off, Still, Subtle, Full) sets it; Subtle (the default) slows the busy pictures to a crawl and leaves Sakura, Dandelion and Moon as designed; Off shows the plain language name. Pressing the space bar still types a space and feels like a normal key.
- [ ] 12.15 **(!)** The pack themes (Angel, Swan, Dragon, Fire Dragon, Cosmo Deep Field, Stardust, Forest, Ocean, Mountains, Cat, Dog, Sunshine, Lanterns, Sunflowers, Lotus Pond): each draws its keys, board and space-bar picture; Angel, Stardust, Sunshine and Lanterns also drift or twinkle over the keys (never blocking a tap); Swan looks soft with no hard outlines; Dragon's board shows faint scale arcs.
- [ ] 12.15b Favourites and shuffle: tap the heart on two or more themes; Shuffle my favourites (Every day / Every week) picks one of them, not the same one two days running; picking a theme yourself turns shuffle off.
- [ ] 12.15b Animations stop when the keyboard is hidden and when the phone's "remove animations" is on (the picture is then still).
- [ ] 12.16 Comic tilts its keys a little; Factory keys have corner rivets; Terminal, Cyberpunk and Factory labels glow or shadow as designed.

## 13. Learned data and privacy
- [ ] 13.1 Learned data lists what Wisp learned (words, swipe style); individual words can be removed; "Erase everything" erases it all after a confirmation.
- [ ] 13.2 Private mode (incognito) in Settings: words typed while it is on are not learned.
- [ ] 13.3 Apps Wisp never learns from: adding an app stops learning in that app.
- [ ] 13.4 Privacy screen lists what is stored, the permissions, and what the app can do on the network (nothing by default).
- [ ] 13.5 Airplane mode on: every feature above still works.
- [ ] 13.6 Settings > Apps > Wisp > Data usage shows no mobile or Wi-Fi data.
- [ ] 13.7 Optional downloader (only if you turn it on): downloads a model from a link you give; with it off, nothing leaves the phone.

## 14. Backup and restore
- [ ] 14.1 Choose a backup folder; "Back up now" writes a file there.
- [ ] 14.2 Automatic backups: turn on, wait for the schedule (or change the clock); a new file appears.
- [ ] 14.3 Restore from a backup after erasing learned data brings the words back.
- [ ] 14.4 Moving settings to another phone: export on one, import on the other, themes and layout choices come across.
- [ ] 14.5 A corrupted or wrong file is refused with a message, and nothing is changed.

## 15. Different fields and apps
- [ ] 15.1 Messaging app (WhatsApp, Messages, Telegram): typing, swiping, emoji, voice, send.
- [ ] 15.2 Browser address bar: URL keyboard with a / key; Enter says Go.
- [ ] 15.3 Browser search box: Enter says Search.
- [ ] 15.4 Email compose: @ key present; To field and body both work.
- [ ] 15.5 Notes app with long text: suggestions and cursor movement stay fast.
- [ ] 15.6 Code or terminal app (Termux): letters, symbols, Ctrl-less typing work; no auto-capitalisation or autocorrect surprises where the app asks for none.
- [ ] 15.7 Fields that ask for no suggestions (passwords, visible passwords, PINs): no strip suggestions, no learning.
- [ ] 15.8 Multi-line fields: Enter inserts a new line; single-line: performs the action.
- [ ] 15.9 Apps with a floating or bottom-sheet text field: the keyboard opens and closes with the sheet; the field stays visible.
- [ ] 15.10 Full-screen landscape text fields (some apps): typing still works.

## 16. Rotation, windows and system behaviour
- [ ] 16.1 Rotate while typing: the keyboard redraws, text and suggestions are kept, no crash.
- [ ] 16.2 Landscape layout is usable (keys not tiny; the strip visible).
- [ ] 16.3 Split-screen and pop-up windows: keyboard still works.
- [ ] 16.4 Foldables or tablets (if you have one): the keyboard sizes to the window; one-handed/floating modes make sense.
- [ ] 16.5 Switching apps while the keyboard is open: it hides and shows without a flash or a wrong theme.
- [ ] 16.6 Switch to another keyboard and back several times: Wisp always returns correctly.
- [ ] 16.7 Lock and unlock the phone with the keyboard open: it comes back working.
- [ ] 16.8 Dark mode on the phone: the keyboard theme you chose stays the same unless you choose a theme that follows the system.
- [ ] 16.9 Gesture navigation and 3-button navigation: the keyboard does not overlap the nav bar or leave a gap under it.
- [ ] 16.10 Display size and font size set to the largest: the keyboard is still usable.
- [ ] 16.11 Accessibility: TalkBack reads keys, the strip suggestions and the settings controls sensibly.
- [ ] 16.12 Hardware keyboard attached: Wisp behaves (hides unless "show soft keyboard with hardware keyboard" is on).

## 17. Performance, memory and battery
- [ ] 17.1 **(!)** Typing feels instant on every key press; no stutter when the strip updates.
- [ ] 17.2 First time the keyboard shows after a restart is fast (under about half a second).
- [ ] 17.3 After an hour of normal use the keyboard has not become slower.
- [ ] 17.4 Settings > Battery: Wisp is a small share after a day of use; it holds no wake lock when hidden.
- [ ] 17.5 Developer options > Running services (or `adb shell dumpsys meminfo app.wisp.keyboard`): typing-only memory is under 80 MB; after a dictation or rewrite and a few minutes idle, the voice process is gone.
- [ ] 17.6 Phone does not get warm while typing, swiping or with the keyboard just sitting open.
- [ ] 17.7 `adb logcat` while typing shows no repeated errors, crashes or ANRs.

## 18. Robustness and edge cases
- [ ] 18.1 Rapidly alternate between letters, symbols, number pad, emoji and clipboard 30 times: no crash, no stuck keys.
- [ ] 18.2 Hold a key, then touch a second key with another finger: both behave sensibly.
- [ ] 18.3 Swipe starting on a non-letter key or off the keys: no crash.
- [ ] 18.4 Paste a very long text (several thousand characters), then type: still responsive.
- [ ] 18.5 Switch language mid-word and mid-swipe: no crash, no lost text.
- [ ] 18.6 Edit mode while the voice panel or clipboard panel is open: it closes or behaves.
- [ ] 18.7 Force-stop Wisp from Settings while it is open: reopening works and the settings are intact.
- [ ] 18.8 Kill the app from recents while dictating: next time it starts clean.
- [ ] 18.9 Low storage and low memory (open many apps): the keyboard still appears.
- [ ] 18.10 Clear the app's storage: next launch shows the Welcome screen and defaults.
- [ ] 18.11 Uninstall and reinstall: no leftover state, the IME list is correct.
- [ ] 18.12 Update over an older build keeps settings, learned words and the chosen theme.

## 19. Sign-off
- [ ] 19.1 Every **(!)** item above passes.
- [ ] 19.2 You used Wisp as your only keyboard for a full day and did not switch away because of a problem.
- [ ] 19.3 Battery, memory and any crashes from that day are written in `docs/perf.md` under "Not yet measured".
