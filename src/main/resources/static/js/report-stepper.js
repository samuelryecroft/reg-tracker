// T7 / roadmap 2.4. Progressively enhances the visitor report form's six <fieldset class="step">
// groups (see fragments/report-fields.html) into a mobile-friendly stepper. Without this script the
// form renders as one flowing page and still submits correctly - that is the no-JS fallback, not a
// degraded mode.
//
// Validation runs on advance only, never while typing (the visitor may be sitting opposite a child;
// the screen must stay calm - see design-perspective.md D.3/2.4). "Save draft" and "Submit for
// review" remain the same two real form submissions as before, just relocated into the sticky footer
// on the last step.
//
// T174 adds the autosave this file previously said it could not add unilaterally: each "Next" posts
// the form to /visitor/interviews/{id}/report/draft, the save-partial-progress endpoint that has
// since been designed with the team. Three things about it are deliberate.
//
// 1. THE WHOLE FORM IS POSTED, NOT THE STEP. The server applies form values as a full replacement
//    and cannot distinguish an absent field from a cleared one, so a per-step payload would blank
//    the steps behind it. The fieldsets are hidden, not disabled, so FormData(form) already carries
//    everything - including the CSRF token.
// 2. ADVANCING NEVER WAITS ON THE NETWORK. The step changes immediately and the save reports itself
//    afterwards. Blocking the wizard on a request is the opposite of a calm screen, and the whole
//    point of saving is that the visitor can carry on.
// 3. SUCCESS IS "200 AND JSON", NOT "200". fetch follows redirects, so an expired session arrives
//    as 200 carrying the login page's HTML with response.ok true. Trusting the status alone would
//    print "Saved" at the exact moment the visitor's work was thrown away.
//
// The two failures are told apart because their remedies are opposite: a terminal refusal (the
// report was submitted or approved while they were typing) can never succeed on retry and stops
// autosave for good, while anything else is transient and worth trying again.
(function () {
    var form = document.querySelector('form[data-js="stepper"]');
    if (!form) {
        return;
    }
    var steps = Array.prototype.slice.call(form.querySelectorAll('fieldset.step'));
    if (steps.length < 2) {
        return;
    }

    var current = 0;
    var chrome = document.createElement('div');
    chrome.className = 'steps';
    chrome.innerHTML =
        '<span class="dots"></span>' +
        '<span class="step-label"></span>' +
        // aria-live, because this is the only thing on the screen that says whether a visitor's
        // work is safe, and it changes without anything moving focus. A save state that reaches
        // only sighted users is the same defect as a state-bearing icon marked aria-hidden.
        '<span class="saved pending" id="stepper-saved" aria-live="polite">Not yet saved</span>';
    form.insertBefore(chrome, steps[0]);
    var dotsEl = chrome.querySelector('.dots');
    var labelEl = chrome.querySelector('.step-label');
    for (var d = 0; d < steps.length; d++) {
        var dot = document.createElement('i');
        dot.className = 'dot';
        dotsEl.appendChild(dot);
    }

    var actionButtons = Array.prototype.slice.call(form.querySelectorAll('button[type="submit"]'));
    var actionRow = actionButtons.length ? actionButtons[0].closest('.btn-row') || actionButtons[0].parentElement : null;
    var footer = document.createElement('div');
    footer.className = 'sticky-actions';
    var backBtn = document.createElement('button');
    backBtn.type = 'button';
    backBtn.className = 'btn ghost';
    backBtn.textContent = '‹ Back';
    var nextBtn = document.createElement('button');
    nextBtn.type = 'button';
    nextBtn.className = 'btn';
    nextBtn.textContent = 'Next ›';
    footer.appendChild(backBtn);
    footer.appendChild(nextBtn);
    if (actionRow) {
        actionRow.parentNode.insertBefore(footer, actionRow.nextSibling);
    } else {
        form.appendChild(footer);
    }

    function fieldsIn(step) {
        return Array.prototype.slice.call(step.querySelectorAll('input, select, textarea'));
    }

    function render() {
        steps.forEach(function (step, i) {
            step.hidden = i !== current;
        });
        var dots = dotsEl.querySelectorAll('.dot');
        dots.forEach(function (dot, i) {
            dot.className = 'dot' + (i < current ? ' done' : i === current ? ' now' : '');
        });
        var legend = steps[current].querySelector('legend');
        labelEl.textContent = 'Step ' + (current + 1) + ' of ' + steps.length +
            (legend ? ' · ' + legend.textContent.trim() : '');
        backBtn.disabled = current === 0;
        var isLast = current === steps.length - 1;
        nextBtn.hidden = isLast;
        if (actionRow) {
            actionRow.style.display = isLast ? '' : 'none';
        }
        footer.style.display = isLast && !actionRow ? 'none' : 'flex';
        steps[current].scrollIntoView({ block: 'start', behavior: 'instant' });
        var firstInvalid = steps[current].querySelector('.is-invalid');
        (firstInvalid || fieldsIn(steps[current])[0] || steps[current]).focus({ preventScroll: true });
    }

    function stepIsValid(step) {
        var valid = true;
        fieldsIn(step).forEach(function (field) {
            if (!field.checkValidity()) {
                valid = false;
                field.reportValidity();
            }
        });
        return valid;
    }

    backBtn.addEventListener('click', function () {
        if (current > 0) {
            current -= 1;
            render();
        }
    });
    nextBtn.addEventListener('click', function () {
        if (!stepIsValid(steps[current])) {
            return;
        }
        if (current < steps.length - 1) {
            current += 1;
            render();
            // After render, never before it: the wizard must not wait on the network (file header,
            // point 2). The visitor is already reading the next step by the time this resolves.
            autosave();
        }
    });

    // Draft/submit are real full-page submissions (see file header). Once one starts, reflect it in
    // the chrome so "Not yet saved" doesn't sit there lying while the request is in flight.
    var savedEl = document.getElementById('stepper-saved');
    form.addEventListener('submit', function (event) {
        var submitter = event.submitter;
        savedEl.textContent = (submitter && submitter.value === 'draft') ? 'Saving draft…' : 'Submitting…';
        savedEl.classList.remove('pending');
    });

    // --- T174 autosave ---------------------------------------------------------------------
    var autosaveUrl = form.getAttribute('data-autosave-url');
    var autosaveStopped = false;

    // One message for every transient failure, deliberately. The client cannot reliably tell an
    // expired session from a dropped connection - a redirected login page and a network error can
    // arrive on different branches of the same fetch - and it does not need to: both leave the work
    // on the page and both are worth retrying. The distinction that matters is transient vs
    // TERMINAL, and that one is never guessed: it is read from the server's own JSON.
    var TRANSIENT = 'Not saved - your work is still on this page. Sign in again if you have been signed out.';

    function state(text, className) {
        savedEl.textContent = text;
        savedEl.className = 'saved' + (className ? ' ' + className : '');
    }

    function isJson(response) {
        var type = response.headers.get('content-type') || '';
        return type.indexOf('application/json') === 0 || type.indexOf('application/json') > 0;
    }

    function autosave() {
        if (!autosaveUrl || autosaveStopped) {
            return;
        }
        state('Saving…', 'pending');
        fetch(autosaveUrl, {
            method: 'POST',
            body: new FormData(form),
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin'
        }).then(function (response) {
            // Not response.ok - see point 3 in the file header. A redirected login page arrives here
            // as a 200 whose body is HTML, and treating that as success is the one failure mode that
            // actively misleads the visitor about whether their work is safe.
            //
            // Honest about what this line is worth: for the login-page case specifically it is belt
            // and braces, because response.json() below would reject on HTML and land in the same
            // catch with the same message - a mutation replacing this with response.ok survives the
            // suite for exactly that reason. It stays because rejecting on the parse is an accident
            // of what the payload happens to contain, not a property of the response, and a gateway
            // or SSO hop that answers 200 with a JSON error envelope would parse cleanly and be
            // read as a save. The check asks the question this code actually means.
            if (!isJson(response)) {
                state(TRANSIENT, 'pending');
                return null;
            }
            return response.json().then(function (body) {
                if (response.status === 409 || (body && body.outcome === 'terminal')) {
                    // Terminal: no retry can succeed, so stop trying and stop implying it might.
                    autosaveStopped = true;
                    state((body && body.message) || 'This report can no longer be saved', 'stopped');
                    return null;
                }
                if (!response.ok) {
                    state(TRANSIENT, 'pending');
                    return null;
                }
                state('Saved ' + (body && body.savedAt ? body.savedAt : ''), '');
                return null;
            });
        }).catch(function () {
            state(TRANSIENT, 'pending');
        });
    }

    render();
})();
