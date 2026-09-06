// D-1e (spec §8n): the narrow-viewport shell panel. Below 900px, .shell-side (brand, org
// identity, the nav, AND .shell-user's log-out button - the only one in the application) used to
// be display:none with nothing substituted. It is restored here as a full-screen panel over the
// SAME markup, toggled by a native <details>/<summary> in the header (fragments/layout.html) -
// opening, closing and every link inside already work with zero script, because app.css shows or
// hides .shell-side purely from that element's own [open] state via :has(). This file is
// enhancement, and only enhancement (D-1e-5): focus management, Escape, and the trap. None of it
// runs without a real click already having opened a real, working panel.
//
// D-1e-4's focus ruling is the OPPOSITE of §8m's section panel, deliberately - not this file
// forgetting what that one does. §8m discloses a choice over a form the visitor is still reading;
// the background stays theirs. This panel covers the entire viewport - there is no "behind" left
// to use, so it traps focus, inerts the rest of the page and locks scroll while open. The
// discriminator is what remains reachable, not how much of the screen is covered.
(function () {
    var disclosure = document.querySelector('.shell-nav-disclosure');
    var toggle = disclosure ? disclosure.querySelector('summary') : null;
    var panel = document.getElementById('shell-side');
    var closeBtn = panel ? panel.querySelector('.shell-nav-close') : null;
    var header = document.querySelector('.shell-header');
    var skipLink = document.querySelector('.skip-link');
    if (!disclosure || !toggle || !panel || !closeBtn) {
        return;
    }

    // Everything but the panel itself, for inert (D-1e-4: the rest of the page is unreachable
    // while this is open). #main is looked up FRESH here rather than cached at script-load time -
    // this fragment is inserted as a sibling BEFORE each page's own <main>, so <main> does not
    // exist in the DOM yet at the point this script tag runs; by the time a user can actually
    // click the toggle the whole page has long since finished loading. Also missing on the two
    // exception-rendered views that skip the shell entirely (GlobalControllerAdvice's own javadoc
    // records why) - filtered out rather than assumed present, for that case too.
    function restOfPage() {
        return [header, document.getElementById('main'), skipLink].filter(function (el) {
            return el != null;
        });
    }

    function focusableIn(root) {
        return Array.prototype.slice.call(root.querySelectorAll(
            'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), ' +
            'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
        )).filter(function (el) {
            // offsetParent is null for a display:none element or one inside one - cheap enough
            // proxy for "is actually reachable" without a full getComputedStyle walk per element.
            return el.offsetParent !== null;
        });
    }

    // D-1e-4: "focus is trapped" - Tab/Shift+Tab cycle within the panel rather than leaving it.
    // inert on restOfPage already removes everything else from the tab order, so in practice
    // there is nothing outside the panel to tab into regardless - this is the explicit version of
    // that same guarantee, for the one thing inert alone does not give: wrapping past the last
    // (or first) focusable element back into the panel, rather than leaving to browser chrome.
    function trapKeydown(event) {
        if (event.key !== 'Tab') {
            return;
        }
        var focusable = focusableIn(panel);
        if (focusable.length === 0) {
            return;
        }
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    }

    function escKeydown(event) {
        if (event.key === 'Escape') {
            // Setting .open fires the SAME 'toggle' event a native summary click does, so the one
            // handler below is the single place open/close state is ever acted on - Escape and
            // the close button are just two ways of reaching it, not two separate close paths
            // that could drift.
            disclosure.open = false;
        }
    }

    function onToggle() {
        if (disclosure.open) {
            // The panel is full-screen (D-1e-3) and paints over the header the toggle lives in,
            // so once JS can offer a real close control INSIDE the panel, the toggle underneath
            // is not just redundant but genuinely unreachable - a click there would land on the
            // panel instead. Swapping which one is hidden (never both, never neither) keeps
            // exactly one working close affordance on screen at all times, in both directions.
            toggle.hidden = true;
            closeBtn.hidden = false;
            // D-1e-4: open -> focus moves to the close control, the panel's first focusable
            // element - a way out is the first thing announced, before anything else in the list.
            restOfPage().forEach(function (el) {
                el.inert = true;
            });
            document.body.style.overflow = 'hidden';
            document.addEventListener('keydown', escKeydown);
            panel.addEventListener('keydown', trapKeydown);
            closeBtn.focus();
        } else {
            // Order matters: inert must clear BEFORE focus moves back to the toggle, or the
            // toggle (inside the still-inert header) would silently refuse the focus call.
            restOfPage().forEach(function (el) {
                el.inert = false;
            });
            toggle.hidden = false;
            closeBtn.hidden = true;
            document.body.style.overflow = '';
            document.removeEventListener('keydown', escKeydown);
            panel.removeEventListener('keydown', trapKeydown);
            toggle.focus();
        }
    }

    disclosure.addEventListener('toggle', onToggle);
    closeBtn.addEventListener('click', function () {
        disclosure.open = false;
    });
})();
