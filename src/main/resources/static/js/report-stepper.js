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

    // One message for every transient failure, deliberately - and NOT because the client cannot
    // tell them apart. It often can. The reason is that what it can tell apart is not what it would
    // need to know.
    //
    // Reaching this branch means "the server did not answer in a way we can read". Measured, an
    // expired session arrives as a 403 from the CSRF filter (no session, so no token to match) -
    // not, as is easy to assume, a followed redirect to a 200 login page. But a gateway 502, a
    // proxy error page and an SSO hop land here identically. So "Sign in again" would be
    // confidently wrong a fair share of the time, and a confidently wrong instruction is worse than
    // a vague one when someone is holding an unsaved account of a child's disclosure. The client
    // asserts only what it can actually read: the server did not accept this, and the work is still
    // on the page.
    //
    // The distinction that does matter - transient vs TERMINAL - is never guessed either. It is
    // read from the server's own outcome field, because only the server can tell a de-allocation
    // 403 from a CSRF 403.
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
            // Not response.ok - see point 3 in the file header. A response the server did not
            // answer in JSON is one we cannot read, whatever its status, and reporting it as a save
            // is the one failure mode that actively misleads a visitor about whether their work is
            // safe.
            //
            // Honest about what this line is worth: response.json() below would also reject on HTML
            // and land in the same catch with the same message, so a mutation replacing this with
            // response.ok survives the suite. It stays because rejecting on the parse is an accident
            // of what the payload happens to contain rather than a property of the response - but
            // the case that genuinely needed closing, a gateway answering 200 with its own JSON
            // envelope, is closed by requiring outcome === 'saved' below, not by this line.
            if (!isJson(response)) {
                state(TRANSIENT, 'pending');
                return null;
            }
            return response.json().then(function (body) {
                // Keyed on the server's own word, never on the status. The status cannot carry this
                // decision: a 403 is a de-allocated visitor (terminal) OR an expired session
                // rejected by the CSRF filter (about as transient as a failure gets), and only the
                // server knows which one it produced.
                if (body && body.outcome === 'terminal') {
                    autosaveStopped = true;
                    state(body.message || 'This report can no longer be saved', 'stopped');
                    return null;
                }
                // Success is asserted, not inferred from the absence of failure. A gateway or SSO
                // hop answering 200 with its own JSON error envelope parses cleanly and has neither
                // outcome nor savedAt - without this it would have been read as a save.
                if (!response.ok || !body || body.outcome !== 'saved') {
                    state(TRANSIENT, 'pending');
                    return null;
                }
                state('Saved ' + (body.savedAt || ''), '');
                return null;
            });
        }).catch(function () {
            state(TRANSIENT, 'pending');
        });
    }

    render();
})();
