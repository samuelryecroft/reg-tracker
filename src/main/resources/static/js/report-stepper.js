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
//
// D-1c/1d (spec §8m) adds real navigation on top of the .dots-only display T7 shipped: the section
// index merges INTO the step label rather than living beside it (D-1d-1) - ".dots show progress that
// cannot be navigated; a section index is navigation that cannot survive the stepper" (a jump link to
// a hidden fieldset scrolls nowhere and never matches :target). The load-bearing case is the
// sent-back loop: a reviewer returns a report with comments about two specific answers, and the
// visitor must be able to reach them without paging through a statutory instrument from section 1.
//
// D-1c-0: this build sits on top of TWO ad-hoc fixes that landed outside the formal queue and are
// PRESERVED, not redrawn - T247 (the server-seeded save state) and T257 (the visitor list's
// "Continue draft" label). They are one behaviour with two commits: T257 promises a draft is
// waiting, T247 is what keeps that promise on this screen. Reverting either makes the other a lie.
// Concretely here: the panel added below carries NO save indicator of its own (a second one is
// exactly the "two sentences that resemble each other" T247 removed), aria-live stays on the
// existing #stepper-saved element with a stable DOM identity, and data-saved-at is still read once,
// on load, from the server - nothing below touches any of that.
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
    var savedAt = form.getAttribute('data-saved-at');
    var chrome = document.createElement('div');
    chrome.className = 'steps';
    chrome.innerHTML =
        '<span class="dots"></span>' +
        '<button type="button" class="step-label" aria-expanded="false" aria-controls="stepper-panel">' +
        '<span class="step-label-text"></span></button>' +
        // aria-live, because this is the only thing on the screen that says whether a visitor's
        // work is safe, and it changes without anything moving focus. A save state that reaches
        // only sighted users is the same defect as a state-bearing icon marked aria-hidden.
        // T247. The initial state comes from the SERVER, because only the server knows whether a
        // draft is already stored. This said "Not yet saved" on every load, including a load of a
        // saved draft whose answers were already filled in on the screen below it - a false
        // statement, made by the one element whose job is to say whether the work is safe.
        //
        // It describes THE WORK ON THIS PAGE, not the row in the database, which is why only the
        // GET carries it. When the form is redisplayed after a validation error it holds edits that
        // were rejected and never stored, and "Not yet saved" is then exactly right.
        //
        // Same words and same formatter the autosave uses, so the state a visitor lands on and the
        // state they watch appear a moment later are one sentence rather than two that resemble
        // each other.
        (savedAt
            ? '<span class="saved" id="stepper-saved" aria-live="polite">Saved ' + savedAt + '</span>'
            : '<span class="saved pending" id="stepper-saved" aria-live="polite">Not yet saved</span>');
    form.insertBefore(chrome, steps[0]);
    var dotsEl = chrome.querySelector('.dots');
    var labelBtn = chrome.querySelector('.step-label');
    var labelTextEl = labelBtn.querySelector('.step-label-text');
    for (var d = 0; d < steps.length; d++) {
        var dot = document.createElement('i');
        dot.className = 'dot';
        dotsEl.appendChild(dot);
    }

    // D-1d-1: the panel this button discloses. Six rows, in document order, "<n>. <legend text>" -
    // the same numbering as the produced document and 1b's reviewerFields(), read live from the DOM
    // rather than hand-written, so a question-set change can never desync the two.  visited tracks
    // "opened at least once this session" (D-1d-2); it is deliberately never cleared, including by
    // Back, because leaving a section does not un-open it.
    var visited = {};
    var panel = document.createElement('ul');
    panel.className = 'step-panel';
    panel.id = 'stepper-panel';
    panel.hidden = true;
    var rows = steps.map(function (step, i) {
        var legend = step.querySelector('legend');
        var li = document.createElement('li');
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'step-panel-row';
        var marker = document.createElement('i');
        marker.className = 'step-panel-marker';
        marker.setAttribute('aria-hidden', 'true');
        var label = document.createElement('span');
        label.className = 'step-panel-row-label';
        label.textContent = (i + 1) + '. ' + (legend ? legend.textContent.trim() : '');
        var attention = document.createElement('span');
        attention.className = 'step-panel-row-attention';
        attention.textContent = 'Needs attention';
        attention.hidden = true;
        btn.appendChild(marker);
        btn.appendChild(label);
        btn.appendChild(attention);
        // D-1d-3: selecting a row is NEVER gated by validity, unlike Next - "I want to be
        // somewhere else" is a different claim from "I have finished this section", and the
        // sent-back loop this control exists for needs a visitor to be able to leave an
        // incomplete section to reach the two answers a reviewer asked about.
        btn.addEventListener('click', function () {
            selectStep(i);
        });
        li.appendChild(btn);
        panel.appendChild(li);
        return { li: li, btn: btn, marker: marker, attention: attention };
    });
    form.insertBefore(panel, steps[0]);

    function closePanel() {
        panel.hidden = true;
        labelBtn.setAttribute('aria-expanded', 'false');
    }

    labelBtn.addEventListener('click', function () {
        if (panel.hidden) {
            renderPanel();
            panel.hidden = false;
            labelBtn.setAttribute('aria-expanded', 'true');
            // D-1d-4: open -> focus moves to the panel's current row.
            rows[current].btn.focus();
        } else {
            // D-1d-4: re-pressing the toggle closes the panel and returns focus to it - a
            // dismissal returns you where you were, unlike a selection which takes you where
            // you chose (render()'s own focus rule handles that case).
            closePanel();
            labelBtn.focus();
        }
    });
    panel.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') {
            closePanel();
            labelBtn.focus();
        }
    });

    function selectStep(i) {
        current = i;
        // render() closes the panel and moves focus itself (D-1d-4: "no new focus rule - reuse
        // the built one"); selecting autosaves on the same terms as Next - after the step
        // changes, never before it.
        render();
        autosave();
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
        // D-1c/1d: any step change closes the panel - it disclosed a choice, the choice is made.
        closePanel();
        visited[current] = true;
        steps.forEach(function (step, i) {
            step.hidden = i !== current;
        });
        var dots = dotsEl.querySelectorAll('.dot');
        dots.forEach(function (dot, i) {
            dot.className = 'dot' + (i < current ? ' done' : i === current ? ' now' : '');
        });
        var legend = steps[current].querySelector('legend');
        labelTextEl.textContent = 'Step ' + (current + 1) + ' of ' + steps.length +
            (legend ? ' · ' + legend.textContent.trim() : '');
        renderPanel();
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

    // reportErrors is the only difference between Next's gate and the panel's read below - the
    // predicate itself (what counts as invalid) is the SAME call in both places, so the two can
    // never disagree (D-1d-2: "the panel asserts nothing the form itself does not already assert").
    function stepIsValid(step, reportErrors) {
        var valid = true;
        fieldsIn(step).forEach(function (field) {
            if (!field.checkValidity()) {
                valid = false;
                if (reportErrors) {
                    field.reportValidity();
                }
            }
        });
        return valid;
    }

    // D-1d-2: four states, and only these - current (where the visitor is), visited (opened at
    // least once), not yet reached (never opened), and needs-attention (checkValidity fails),
    // which is an independent overlay on top of whichever of the first three applies. Deliberately
    // NO "N not answered" count here - see the file's own D-1d-2 note in report-fields.html's
    // sibling spec: 1b's count is computed server-side from a stored report and would be stale the
    // instant a visitor on THIS screen answers anything, and a client-side count-the-blanks repeats
    // T233 (ifNotWhyLate is conditional; a blank means two opposite things).
    function renderPanel() {
        rows.forEach(function (row, i) {
            row.btn.className = 'step-panel-row ' +
                (i === current ? 'current' : visited[i] ? 'visited' : 'not-reached');
            if (i === current) {
                row.btn.setAttribute('aria-current', 'step');
            } else {
                row.btn.removeAttribute('aria-current');
            }
            var needsAttention = !stepIsValid(steps[i], false);
            row.btn.classList.toggle('needs-attention', needsAttention);
            row.attention.hidden = !needsAttention;
        });
    }

    backBtn.addEventListener('click', function () {
        if (current > 0) {
            current -= 1;
            render();
        }
    });
    nextBtn.addEventListener('click', function () {
        if (!stepIsValid(steps[current], true)) {
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
            // Honest about what this line is worth, because the first version of this comment
            // overclaimed it. It was kept for the gateway-answers-200-with-its-own-JSON case; that
            // case is actually closed by requiring outcome === 'saved' below, which is pinned by a
            // test. Replacing this line with response.ok still survives the suite, and after
            // looking for one I cannot construct an input where the two differ: response.json()
            // rejects on anything this would have caught, and lands in the same branch.
            //
            // So it stays for a reason that is about the code rather than about behaviour: without
            // it, every non-JSON response - the ordinary expired-session case - reaches the parse
            // and is handled by an exception, which is a worse thing to read and a worse thing to
            // debug than a stated condition. It is not load-bearing, no test pins it, and it should
            // not be described as though it were.
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
