(function () {
    // Mirrors Role.getDisplayName() so this note and the role chips speak with one vocabulary.
    // Duplicated rather than injected because this script is static and un-templated; the pair is
    // asserted by RoleDisplayNameParityTest, so they cannot drift apart without a red build.
    var LABELS = {
        HOME_STAFF: 'Home Staff', ORG_ADMIN: 'Org Admin', COORDINATOR: 'Coordinator',
        VISITOR: 'Visitor', REVIEWER: 'Reviewer', VIEWER: 'Viewer', ADMIN: 'Admin'
    };
    var SOLO_ROLES = ['HOME_STAFF', 'ADMIN'];
    var CARE_PROVIDER_ROLES = ['VIEWER'];
    var SUPPLIER_ROLES = ['COORDINATOR', 'VISITOR', 'REVIEWER'];
    var roleCheckboxes = document.querySelectorAll('input[name="roles"]');
    var organisationField = document.getElementById('organisationField');
    var homesField = document.getElementById('homesField');
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
                    // LABELS[...] not the raw value: the chips beside this note now read "Home
                    // Staff" (Role.getDisplayName, T119 4d), and a note answering "why can I not
                    // tick this" in a different vocabulary from the thing it is about makes the
                    // reader do the mapping. Falls back to the value so a role added to the enum
                    // without a label here still produces a sentence rather than "A undefined".
                    ? 'A ' + (LABELS[checkedSolo] || checkedSolo) + ' account cannot also hold any other role.'
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
        if (organisationField) {
            var needsOrg = selected.some(function (r) { return r !== 'HOME_STAFF' && r !== 'ADMIN'; });
            organisationField.style.display = needsOrg ? '' : 'none';
        }
        if (homesField) {
            // One Homes field for both roles that have homes (T116): HOME_STAFF and VIEWER used to
            // be two separate controls backed by two separate tables.
            var needsHomes = selected.indexOf('HOME_STAFF') !== -1 || selected.indexOf('VIEWER') !== -1;
            homesField.style.display = needsHomes ? '' : 'none';
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
