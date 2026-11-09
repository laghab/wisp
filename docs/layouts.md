# Layout files

Layouts are JSON files in `app/src/main/res/raw/layout_*.json`. Wisp reads them with `LayoutParser`, then `LayoutGuard`
adds anything a layout may not omit (today: the switch-keyboard key). Imported layouts (task 8.3) use the same format.

## Format

```json
{
  "name": "example",
  "rows": [
    ["q", "w", "e", "r", "t", "y"],
    [{"pad": 0.5}, "a", "s", "d", "f", {"c": "g", "pop": "ĝğ"}, {"pad": 0.5}],
    [{"k": "shift", "w": 1.5}, "z", "x", "c", {"k": "backspace", "w": 1.5}],
    [{"k": "symbols", "w": 1.5}, {"k": "switch"}, {"k": "emoji"}, {"k": "space", "w": 3}, {"k": "period"}, {"k": "enter", "w": 1.5}]
  ]
}
```

- `rows`: a list of rows, top to bottom. All rows share the same height.
- A key is either a one-character string (`"q"`, a letter or symbol that types itself) or an object.
- Widths are relative: each row's keys share the row width in proportion to their `w` (default 1).
- Object fields:

| Field | Meaning |
|---|---|
| `c` | the character the key types (one code point) |
| `k` | a named key: `shift`, `backspace`, `enter`, `symbols`, `abc`, `more`, `switch`, `emoji`, `space`, `comma`, `period` |
| `w` | relative width, must be positive |
| `label` | text drawn on the key (overrides the default) |
| `pad` | an empty gap of this relative width (takes no key, draws nothing) |
| `pop` | characters offered on long press (parsed now, shown once long-press popups land in task 8.3) |

## Rules
- Every row needs at least one key; a key needs `c`, `k` or `pad`.
- The switch-keyboard key cannot be left out: if a layout has none, one is added to the bottom row.
- Errors name the row and key, for example `Row 2 key 3: "ab" must be a single character`.

## Panels shipped
`layout_qwerty` (plus `_email` and `_url` variants), `layout_number`, `layout_symbols`, `layout_symbols_more`,
`layout_emoji_bar` (the emoji grid itself is `EmojiGridView`, fed from `res/raw/emoji.txt`).
