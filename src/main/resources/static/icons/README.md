# Vendored icons

`phosphor.svg` is a self-hosted subset of [Phosphor Icons](https://phosphoricons.com)
(`@phosphor-icons/core@2.1.1`, MIT licence — https://github.com/phosphor-icons/core/blob/main/LICENSE),
vendored per the T119 Nocturne foundation: no CDN link, no runtime fetch to
unpkg/phosphoricons.com, no referrer leak.

It is a single `<svg style="display:none">` sprite of `<symbol>` elements, one per icon,
included once from `fragments/layout.html`. Reference an icon from a template with:

```html
<svg class="icon" aria-hidden="true"><use href="#ph-bell"></use></svg>
```

Add a new icon by fetching its source SVG from the same package/version and appending a
`<symbol id="ph-{name}" viewBox="...">{inner markup}</symbol>` entry — keep the version pinned
so the whole subset stays from one release.

Current subset (20 symbols): magnifying-glass, eye-slash, moon, sun, bell, caret-up-down,
squares-four (+ fill), tray (+ fill), seal-check (+ fill), users-three, house-line,
clock-counter-clockwise, user-focus, sign-out, buildings, users, palette.

The last three (buildings, users, palette) cover shell nav items for routes the sampled
mockup screens didn't happen to draw a sidebar for (Organisations, Users, Branding) —
`buildings` is the design system's own canonical icon for organisation/home content
(used in screen 4e); `users` and `palette` are the closest reasonable Phosphor match,
chosen rather than reusing an unrelated icon already in the set.
