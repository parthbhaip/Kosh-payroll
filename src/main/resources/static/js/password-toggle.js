// Show or hide a password: <button type="button" data-password-toggle aria-controls="password" hidden>Show</button>.
// The button stays hidden without JavaScript, which leaves a plain password field.
(function () {
	'use strict';
	document.querySelectorAll('[data-password-toggle]').forEach(function (button) {
		var input = document.getElementById(button.getAttribute('aria-controls'));
		if (!input) {
			return;
		}
		button.hidden = false;
		button.addEventListener('click', function () {
			var show = input.type === 'password';
			input.type = show ? 'text' : 'password';
			button.textContent = show ? 'Hide' : 'Show';
			button.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
		});
		// Submit it as a password field, so the browser offers to save it as one.
		if (input.form) {
			input.form.addEventListener('submit', function () {
				input.type = 'password';
			});
		}
	});
})();
