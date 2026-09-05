(function () {
    // The "Go back" control on 6e. Rendered hidden and revealed here, rather than rendered visible
    // and wired here: this is the error page, so it is the page most likely to be reached with
    // something already broken, and a visible control that silently does nothing is worse than an
    // absent one. history.length > 1 because a page opened directly from a pasted link has nowhere
    // to go back TO - back() would either do nothing or leave the site.
    var back = document.querySelector('[data-history-back]');
    if (!back || window.history.length <= 1) {
        return;
    }
    back.hidden = false;
    back.addEventListener('click', function () {
        window.history.back();
    });
})();
