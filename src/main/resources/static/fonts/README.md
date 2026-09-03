# Vendored fonts

`inter-latin-variable.woff2` is [Inter](https://rsms.me/inter/) (SIL Open Font License 1.1 —
https://github.com/rsms/inter/blob/master/LICENSE.txt), self-hosted per T119 spec §5c R-Q10: no
CDN (`fonts.googleapis.com`/`fonts.gstatic.com`), no build step. Fetched from Google Fonts' own
served file (`v20`, Latin subset, variable weight axis) rather than the family's own release
assets, since that's the exact byte-identical file a `<link>` to the CDN would otherwise have
pulled at runtime — vendoring it removes the CDN round-trip and the referrer leak, not the font.

Why self-hosted rather than `system-ui`: Nocturne's one hard type rule is headings at weight 500,
never heavier. Windows' system stack (Segoe UI) has Regular 400 and Semibold 600 but no 500, so a
system-font heading either drops to 400, jumps to 600, or gets browser-synthesised — the one thing
the design system asks not to happen.

It's a **variable font** — one file covers the whole weight axis, so 400 (body) and 500 (heading)
both resolve from it via a single `@font-face` with `font-weight: 400 500` (a range, not two
files). `font-display: swap` avoids invisible text while it loads; `system-ui` sits behind it in
`--font-heading`/`--font-body` as the fallback (and the only stack used at all until this
question was resolved), which itself "honours the never-heavier-than-500 rule fine" per the
design system's own readme, so nothing looks broken during the swap.

To update: fetch the same URL pattern from `https://fonts.googleapis.com/css2?family=Inter:wght@400;500&display=swap`
with a modern desktop user-agent (the API serves different formats/subsets by user-agent) and
take the `unicode-range: U+0000-00FF, ...` (Latin) block's `src` URL - keep the version-numbered
directory (`v20` at time of writing) in sync with the `unicode-range` comment below it in app.css.
