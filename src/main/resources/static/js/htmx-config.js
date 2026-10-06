// htmx settings for every page. fragments/head loads htmx and then this file, both deferred, so these run before
// htmx initialises on DOMContentLoaded.
(function () {
	'use strict';
	// The CSP blocks injected <style> tags; the indicator styles live in app.css instead.
	htmx.config.includeIndicatorStyles = false;
	// No hx-on or js: expressions; the CSP forbids eval anyway.
	htmx.config.allowEval = false;

	// Spring Security's CSRF token on every htmx request, from the meta tags written for signed-in users.
	document.addEventListener('htmx:configRequest', function (event) {
		var token = document.querySelector('meta[name="_csrf"]');
		var header = document.querySelector('meta[name="_csrf_header"]');
		if (token && header) {
			event.detail.headers[header.content] = token.content;
		}
	});
})();
