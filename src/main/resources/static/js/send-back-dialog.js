// T173(1b) / spec §6a D-1b-5, corrected per Creed's #71 design review: the trigger is a REAL
// type="submit" (name="action" value="reject", same as the dialog's own button) rather than a
// dead type="button" - the no-JS fallback pointed the wrong way before this. Approve is a real
// submit and works without JS; the trigger being a dead button meant a broken/disabled script let
// a reviewer approve (irreversible) but not send back (reversible) - exactly backwards for a
// safeguarding surface, where a broken script must never create pressure toward the irreversible
// action. This script's only job now is to intercept that submit and open the dialog instead -
// preventDefault() on a submit button's click stops the actual POST, so with JS the behaviour is
// unchanged from before. Without JS (or if this script fails to load/run), clicking the trigger
// posts action=reject with whatever reviewComments currently holds (typically blank) - the
// server's existing validation catches it and the dialog reopens on the re-render via its own
// `open` attribute (see reviewer/review-form.html), so the reviewer reaches the same complete
// flow one round-trip later. Never a dead end - D-1a-3's own principle, applied here.
//
// showModal()/close() give focus-trap, Escape-to-close and an inert page behind the dialog for
// free - none of that is implemented here.
//
// T285: THIS SCRIPT NOW OWNS THE `required` ATTRIBUTE, and that is the fix rather than a tidy-up.
// The textarea used to carry a static `required` while living inside a CLOSED dialog, so pressing
// "Approve and generate document" - a submit in the same form - ran native constraint validation,
// found an empty required control it could not focus, and silently refused to submit. The end of
// the whole safeguarding workflow did nothing, with no server round-trip to leave a trace.
//
// The requirement is real, but it belongs to the send-back path only, and ReviewerController
// already enforces it there. Setting it from the dialog's own open state makes the requirement
// DERIVE from the visibility rather than agree with it by coincidence - the two cannot drift apart
// again, because there is only one of them now.
(function () {
    var openBtn = document.getElementById('openSendBackDialog');
    var dialog = document.getElementById('sendBackDialog');
    var cancelBtn = document.getElementById('cancelSendBack');
    var comments = document.getElementById('reviewComments');
    if (!openBtn || !dialog) {
        return;
    }

    // One function, called from every place the path changes, so no caller can set one and forget
    // the other. `aria-invalid` moves with it because the SECOND defect in the same element was the
    // aria state and the validity state disagreeing: the field was announced as fine at the exact
    // moment it was blocking submission. A control that is not required cannot be invalid for being
    // empty, so leaving aria-invalid behind would restate the same contradiction in a new place.
    function setSendBackPathActive(active) {
        if (!comments) {
            return;
        }
        comments.required = active;
        if (!active) {
            comments.setAttribute('aria-invalid', 'false');
        }
    }

    // The server re-renders with the dialog already open when it rejected a blank comment, so the
    // requirement has to be armed for that path too - it is the same state, arrived at differently.
    setSendBackPathActive(dialog.hasAttribute('open'));

    openBtn.addEventListener('click', function (event) {
        event.preventDefault();
        setSendBackPathActive(true);
        dialog.showModal();
    });
    if (cancelBtn) {
        cancelBtn.addEventListener('click', function () {
            setSendBackPathActive(false);
            dialog.close();
        });
    }
    // Escape closes a <dialog> natively without any click, so without this the requirement would
    // survive a cancel the script never saw - and approve would be blocked again, by the same
    // mechanism, for anyone who opened send-back and thought better of it.
    dialog.addEventListener('close', function () {
        setSendBackPathActive(false);
    });

    if (comments) {
        // Announce what the browser actually thinks, at the moment it thinks it.
        comments.addEventListener('invalid', function () {
            comments.setAttribute('aria-invalid', 'true');
        });
        comments.addEventListener('input', function () {
            comments.setAttribute('aria-invalid', String(!comments.checkValidity()));
        });
    }
})();
