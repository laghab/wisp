# Themes

A theme is one JSON file with named tokens. The 11 built-in themes are in `app/src/main/assets/themes/`; imported ones are kept in the app's `files/themes/`. `ThemeParser` reads them and rejects a file with a missing token or a bad value, naming it.

## Required tokens
`id` (lowercase letters, digits, dashes), `name`, `board`, `key`, `mod` (modifier keys), `accent`, `fg`, `modFg`, `accentFg`, `radius`, `font`, `size`, `stripFg`, `sep`.

## Optional tokens
`dark`, `border` (`{width, color}`), `shadow` and `lit` (lists of shadows; `lit` replaces `shadow` on a key being pressed or on a swipe path), `weight`, `upper`, `track` (letter spacing in em), `cut` (chamfered corners, dp), `band` and `bandHeight` (a strip above the keys), `micGlow`, `space` (label on the space bar), `blur`, `dynamic`.

## Light and dark looks
A theme can have both a light and a dark look and still be ONE theme: the file holds the tokens the two share, plus a `"variants"` object with `"light"` and `"dark"` entries listing the tokens that differ (any token, including `board`, `key`, `fg`, shadows and `band`). The user's **Keyboard colours** setting (System, Light, Dark) picks the look; System follows the phone. A theme without `"variants"` has one look and ignores the setting.
```json
{ "id": "duo", "name": "Duo", "board": "#111", "font": "inter", ...,
  "variants": { "dark": { "board": "#111" }, "light": { "board": "#f0f0f0", "fg": "#111" } } }
```

## Values
- **Colour:** `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgba(r,g,b,a)` or `transparent`.
- **Fill** (`board`, `key`, `mod`, `accent`, `band`): a colour, or `{"linear": {"angle", "length", "stops"}}` (180 is top to bottom; `length` in dp makes the gradient repeat, which is how stripes and grids are made), `{"radial": {"x", "y", "rx", "ry", "stops"}}` (a soft blob, `x` and `y` as fractions of the board), or `{"dots": {"step", "radius", "color"}}`. `board` may be a list of fills drawn bottom first.
- **Shadow:** `{"inset", "dx", "dy", "blur", "spread", "color"}`; a drop shadow with spread and no blur makes a ring.
- **radius:** one number, or four (top-left, top-right, bottom-right, bottom-left). Lengths are dp.
- **font:** `sans` (the system font) or one of the bundled ids: `inter`, `space-grotesk`, `orbitron`, `vt323`, `bangers`, `zen-maru`, `chakra-petch`. The file used is `res/font/<id>_w<weight>.ttf`.

## How they are drawn
`ThemeRenderer` draws each key background (outside shadows, fill, inset shadows, border) once per size and look into a bitmap, then only blits it, so a frame costs a few `drawBitmap` calls whatever the theme. A key that is pressed or on a swipe path uses the `lit` look in the accent fill.

## Material You and photos
The `material` theme has `dynamic: true`: with "Colour from wallpaper" on (Android 12 and later) its colours come from the system palette (`DynamicColours`). A photo background with dimming is set in the style options.
