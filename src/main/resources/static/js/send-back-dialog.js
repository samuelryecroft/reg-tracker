// T173(1b) / spec §6a D-1b-5. Progressively enhances the "Send back with comments" trigger into
// opening the native <dialog> instead of doing nothing - without this script the button is a
// plain type="button" that goes nowhere, which is why the trigger is NOT the "reject" submitter
// itself: the real submit button lives inside the dialog, and a browser with JS disabled simply
// has no way to reach it. That is a real gap for this one path (unlike report-stepper.js, whose
// no-JS fallback is the full flowing form), accepted because a review decision - approve or send
// back - is exactly the kind of action this app should not make reachable without confirming the
// reviewer actually saw what they were agreeing to.
//
// showModal()/close() give focus-trap, Escape-to-close and an inert page behind the dialog for
// free - none of that is implemented here. If the server round-trip re-renders with a
// reviewComments validation error, the dialog is already open via its own `open` attribute in the
// markup (see reviewer/review-form.html), so the error is visible on load with no script needed.
(function () {
    var openBtn = document.getElementById('openSendBackDialog');
    var dialog = document.getElementById('sendBackDialog');
    var cancelBtn = document.getElementById('cancelSendBack');
    if (!openBtn || !dialog) {
        return;
    }
    openBtn.addEventListener('click', function () {
        dialog.showModal();
    });
    if (cancelBtn) {
        cancelBtn.addEventListener('click', function () {
            dialog.close();
        });
    }
})();
