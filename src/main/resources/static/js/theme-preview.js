// T119 3a / spec §7j-7k, D-3a-3/D-3a-6/D-3a-7. Progressively enhances the branding form's colour
// picker with a live, client-side preview - no round trip, because a reload can only show what was
// already saved, and the unsaved state is the entire point.
//
// WITHOUT THIS SCRIPT the two preview panes still render, server-side, at the currently SAVED
// brand hue (see admin/theme-form.html) - picking a new colour just doesn't move them until the
// form is saved and the page reloads. That is the no-JS fallback: degrade to a static preview of
// the stored state, never to a broken or misleading one (§5h).
//
// WITH THIS SCRIPT, on every input event from the colour picker, both panes' own --brand-hue custom
// property is updated directly (one line of effect: setProperty). Because every accent token in
// app.css is declared as oklch(L C var(--brand-hue)), and a var() inside a custom property's value
// is substituted PER ELEMENT, the entire nine-step ramp re-derives for each pane's subtree from that
// one write - nothing else needs updating, and nothing else is copied (D-3a-6's own CSS finding:
// the panes get their appearance's whole token set by being added to app.css's existing
// [data-appearance="light"/dark] selector lists, not by a second copy of them).
//
// THE HUE MATH IS A DELIBERATE, EXACT PORT OF AccentRamp.hueFrom (Java) - not an approximation.
// Creed's ruling (spec §7k, D-3a-7): AccentRamp's own javadoc already states "the two must agree
// exactly", sRGB-HSL and OKLCH hue diverge worst through the blues (an approximation would be
// faithful for some brand colours and visibly wrong for others, with no way for the person picking
// to tell which case they're in), and a preview known to be provisional is trusted by nobody.
// AccentRamp.java carries the reciprocal comment naming this file, so a future change to one side
// is at least discoverable from the other.
(function () {
    var picker = document.getElementById('primaryColor');
    var panes = document.querySelectorAll('.theme-preview');
    if (!picker || panes.length === 0) {
        return;
    }

    // Below this chroma a colour is effectively grey and its hue is rounding noise - ported
    // constant-for-constant from AccentRamp.GREY_CHROMA_FLOOR / NEUTRAL_HUE. Omitting this branch
    // would preview an arbitrary vivid hue for a near-grey pick while the server stores neutral -
    // the most visible possible disagreement, on the least obvious input.
    var GREY_CHROMA_FLOOR = 0.02;
    var NEUTRAL_HUE = 265;

    function srgbChannelToLinear(value255) {
        var normalised = value255 / 255;
        return normalised <= 0.04045 ? normalised / 12.92 : Math.pow((normalised + 0.055) / 1.055, 2.4);
    }

    function linearFromHex(hex) {
        var clean = hex.replace('#', '');
        return [
            srgbChannelToLinear(parseInt(clean.substring(0, 2), 16)),
            srgbChannelToLinear(parseInt(clean.substring(2, 4), 16)),
            srgbChannelToLinear(parseInt(clean.substring(4, 6), 16))
        ];
    }

    function linearRgbToOklab(red, green, blue) {
        var l = 0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue;
        var m = 0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue;
        var s = 0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue;
        var lRoot = Math.cbrt(l);
        var mRoot = Math.cbrt(m);
        var sRoot = Math.cbrt(s);
        return [
            0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot,
            1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot,
            0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot
        ];
    }

    // Same rounding as AccentRamp.hueFrom, and only here - a preview that rounds its own way could
    // disagree with the server by a degree on some colours.
    function floorMod360(degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    function hueFrom(hex) {
        var rgb = linearFromHex(hex);
        var lab = linearRgbToOklab(rgb[0], rgb[1], rgb[2]);
        var a = lab[1];
        var b = lab[2];
        if (Math.hypot(a, b) < GREY_CHROMA_FLOOR) {
            return NEUTRAL_HUE;
        }
        return floorMod360(Math.round((Math.atan2(b, a) * 180) / Math.PI));
    }

    function applyHue(hex) {
        var hue = hueFrom(hex);
        panes.forEach(function (pane) {
            pane.style.setProperty('--brand-hue', String(hue));
        });
    }

    picker.addEventListener('input', function () {
        applyHue(picker.value);
    });
})();
