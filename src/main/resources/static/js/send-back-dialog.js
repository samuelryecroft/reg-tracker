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
(function () {
    var openBtn = document.getElementById('openSendBackDialog');
    var dialog = document.getElementById('sendBackDialog');
    var cancelBtn = document.getElementById('cancelSendBack');
    if (!openBtn || !dialog) {
        return;
    }
    openBtn.addEventListener('click', function (event) {
        event.preventDefault();
        dialog.showModal();
    });
    if (cancelBtn) {
        cancelBtn.addEventListener('click', function () {
            dialog.close();
        });
    }
})();
