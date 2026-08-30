(function () {
    var SOLO_ROLES = ['HOME_STAFF', 'ADMIN'];
    var CARE_PROVIDER_ROLES = ['VIEWER'];
    var SUPPLIER_ROLES = ['COORDINATOR', 'VISITOR', 'REVIEWER'];
    var roleCheckboxes = document.querySelectorAll('input[name="roles"]');
    var homeField = document.getElementById('homeField');
    var organisationField = document.getElementById('organisationField');
    var viewerHomesField = document.getElementById('viewerHomesField');
    function selectedRoles() {
        return Array.from(roleCheckboxes).filter(function (cb) { return cb.checked; }).map(function (cb) { return cb.value; });
    }
    // HOME_STAFF and ADMIN can't be combined with any other role, and VIEWER (a Care Provider
    // org role) can't be combined with COORDINATOR/VISITOR/REVIEWER (Supplier org roles) - grey
    // out whichever checkboxes would create an illegal combination, without touching ones
    // already checked.
    function updateRoleConstraints() {
        var selected = selectedRoles();
        var checkedSolo = selected.find(function (v) { return SOLO_ROLES.indexOf(v) !== -1; });
        var checkedOrgScoped = selected.some(function (v) { return SOLO_ROLES.indexOf(v) === -1; });
        var hasCareProviderRole = selected.some(function (v) { return CARE_PROVIDER_ROLES.indexOf(v) !== -1; });
        var hasSupplierRole = selected.some(function (v) { return SUPPLIER_ROLES.indexOf(v) !== -1; });
        roleCheckboxes.forEach(function (cb) {
            var isSolo = SOLO_ROLES.indexOf(cb.value) !== -1;
            var wouldConflict = (checkedSolo && cb.value !== checkedSolo) || (checkedOrgScoped && isSolo)
                || (hasSupplierRole && CARE_PROVIDER_ROLES.indexOf(cb.value) !== -1)
                || (hasCareProviderRole && SUPPLIER_ROLES.indexOf(cb.value) !== -1);
            cb.disabled = wouldConflict && !cb.checked;
            cb.closest('.checkbox-option').classList.toggle('disabled', cb.disabled);
        });
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
        cb.addEventListener('change', function () {
            updateRoleConstraints();
            toggleFields();
        });
    });
    updateRoleConstraints();
    toggleFields();
})();
