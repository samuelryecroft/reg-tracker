// T7 / roadmap 2.4. Progressively enhances the visitor report form's six <fieldset class="step">
// groups (see fragments/report-fields.html) into a mobile-friendly stepper. Without this script the
// form renders as one flowing page and still submits correctly - that is the no-JS fallback, not a
// degraded mode.
//
// Validation runs on advance only, never while typing (the visitor may be sitting opposite a child;
// the screen must stay calm - see design-perspective.md D.3/2.4). "Save draft" and "Submit for
// review" remain the same two real form submissions as before, just relocated into the sticky footer
// on the last step - this script does not talk to the server on its own and does not implement
// autosave; per-step autosave would need a new save-partial-progress endpoint, which is a backend
// decision to make with the team rather than something to add unilaterally here.
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
        '<span class="saved pending" id="stepper-saved">Not yet saved</span>';
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

    render();
})();
