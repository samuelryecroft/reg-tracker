(function () {
    var SOLO_ROLES = ['HOME_STAFF', 'ADMIN'];
    var CARE_PROVIDER_ROLES = ['VIEWER'];
    var SUPPLIER_ROLES = ['COORDINATOR', 'VISITOR', 'REVIEWER'];
    var roleCheckboxes = document.querySelectorAll('input[name="roles"]');
    var homeField = document.getElementById('homeField');
    var organisationField = document.getElementById('organisationField');
    var viewerHomesField = document.getElementById('viewerHomesField');
    var constraintNote = document.getElementById('roleConstraintNote');
    function selectedRoles() {
        return Array.from(roleCheckboxes).filter(function (cb) { return cb.checked; }).map(function (cb) { return cb.value; });
    }
    // HOME_STAFF and ADMIN can't be combined with any other role, and VIEWER (a Care Provider
    // org role) can't be combined with COORDINATOR/VISITOR/REVIEWER (Supplier org roles). Rather than
    // disabling the conflicting checkboxes - which pulls them out of the tab order and leaves the
    // rule unstated (FE-07) - mark them aria-disabled, block the click that would create the illegal
    // combination, and say which rule is in force in a visible, aria-live note next to the group.
    function updateRoleConstraints() {
        var selected = selectedRoles();
        var checkedSolo = selected.find(function (v) { return SOLO_ROLES.indexOf(v) !== -1; });
        var checkedOrgScoped = selected.some(function (v) { return SOLO_ROLES.indexOf(v) === -1; });
        var hasCareProviderRole = selected.some(function (v) { return CARE_PROVIDER_ROLES.indexOf(v) !== -1; });
        var hasSupplierRole = selected.some(function (v) { return SUPPLIER_ROLES.indexOf(v) !== -1; });
        var activeRule = '';
        roleCheckboxes.forEach(function (cb) {
            var isSolo = SOLO_ROLES.indexOf(cb.value) !== -1;
            var wouldConflict = (checkedSolo && cb.value !== checkedSolo) || (checkedOrgScoped && isSolo)
                || (hasSupplierRole && CARE_PROVIDER_ROLES.indexOf(cb.value) !== -1)
                || (hasCareProviderRole && SUPPLIER_ROLES.indexOf(cb.value) !== -1);
            var blocked = wouldConflict && !cb.checked;
            cb.setAttribute('aria-disabled', blocked ? 'true' : 'false');
            cb.closest('.checkbox-option').classList.toggle('disabled', blocked);
            if (blocked && !activeRule) {
                activeRule = checkedSolo
                    ? 'A ' + checkedSolo + ' account cannot also hold any other role.'
                    : hasSupplierRole
                        ? 'A Home Staff/Admin-style account cannot also hold a Viewer role alongside Coordinator, Visitor or Reviewer.'
                        : 'This role cannot be combined with the roles already selected.';
            }
        });
        if (constraintNote) {
            constraintNote.textContent = activeRule;
        }
    }
    function toggleFields() {
        var selected = selectedRoles();
        if (homeField) {
            homeField.style.display = selected.indexOf('HOME_STAFF') !== -1 ? '' : 'none';
        }
        if (organisationField) {
            var needsOrg = selected.some(function (r) { return r !== 'HOME_STAFF' && r !== 'ADMIN'; });
            organisationField.style.display = needsOrg ? '' : 'none';
        }
        if (viewerHomesField) {
            viewerHomesField.style.display = selected.indexOf('VIEWER') !== -1 ? '' : 'none';
        }
    }
    roleCheckboxes.forEach(function (cb) {
        cb.addEventListener('click', function (event) {
            // aria-disabled (unlike the disabled attribute) does not stop the browser from checking
            // the box, so the illegal combination has to be vetoed here, after the fact, on the same
            // interaction - the option stays reachable and its state stays announced either way.
            if (cb.getAttribute('aria-disabled') === 'true' && cb.checked) {
                event.preventDefault();
                cb.checked = false;
                return;
            }
            updateRoleConstraints();
            toggleFields();
        });
    });
    updateRoleConstraints();
    toggleFields();
})();
