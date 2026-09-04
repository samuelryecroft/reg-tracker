# Vendored icons

`phosphor.svg` is a self-hosted subset of [Phosphor Icons](https://phosphoricons.com)
(`@phosphor-icons/core@2.1.1`, MIT licence — https://github.com/phosphor-icons/core/blob/main/LICENSE),
vendored per T119 spec §7 (the design's canonical 55-icon list, both weights): no CDN link,
no runtime fetch to unpkg/phosphoricons.com, no referrer leak.

It is a single `<svg style="display:none">` sprite of `<symbol>` elements, one per icon per
weight, included once from `fragments/layout.html`. Reference an icon from a template with:

```html
<svg class="icon" aria-hidden="true"><use th:href="${ico} + '#ph-bell'"></use></svg>
<svg class="icon" aria-hidden="true"><use th:href="${ico} + '#ph-fill-seal-check'"></use></svg>
```

`ico` is the resolved sprite URL, bound once via `th:with="ico=@{/icons/phosphor.svg}"` on the
fragment (see `layout.html`) so it's resolved once rather than per icon.

Every icon that is the *only* content of a control needs an accessible name (`aria-label` on the
control, or a visible label the `<svg>` sits beside with `aria-hidden="true"`, never both silently).

## The 55, both weights (110 symbols)

archive · arrow-left · arrow-right · arrow-u-up-left · battery-medium · bell · buildings ·
calendar-blank · calendar-check · caret-down · caret-left · caret-right · caret-up-down ·
cell-signal-medium · check-circle · circle-dashed · clock-countdown · clock-counter-clockwise ·
cloud-check · cloud-slash · compass · dot-outline · download-simple · eye-slash · eyedropper ·
file-doc · file-text · funnel · house-line · info · list · list-numbers · lock-simple ·
magnifying-glass · microphone · moon · package · paper-plane-tilt · pencil-simple · plus ·
printer · prohibit · quotes · seal-check · sign-out · squares-four · sun · tray · user-focus ·
users-three · warning-circle · wifi-high · wifi-slash · x · x-circle

Symbol ids: `ph-{name}` (regular) and `ph-fill-{name}` (fill) — matching the icon name exactly,
no other renaming.

`microphone` (dictate), `cloud-slash` / `wifi-slash` / `cell-signal-medium` / `battery-medium`
(offline affordances) belong to the **held** A1 scope (see the design spec) — vendored here so
they're ready, but not wired into any screen until A1 is answered. Everything else is either in
use in the phase-1 shell or reserved for a named later-phase screen per the spec's screen map.

## Adding or changing an icon

Fetch the source SVG from the same package/version (`@phosphor-icons/core@2.1.1`, regular or
fill directory) and append a `<symbol id="ph-{name}" viewBox="...">{inner markup}</symbol>` (or
`ph-fill-{name}` for the fill weight) entry — keep the version pinned so the whole subset stays
from one release. Don't vendor a 56th icon without adding it to spec §7 first; the list is meant
to stay a closed, deliberate set, not grow ad hoc per screen.
