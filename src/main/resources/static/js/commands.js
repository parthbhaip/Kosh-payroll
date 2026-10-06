// Opens and closes <dialog> modals and drawers from <button commandfor="id" command="show-modal|close">.
// Current browsers do this natively (Invoker Commands API); this only fills in for older ones.
(function () {
	'use strict';
	if ('commandForElement' in HTMLButtonElement.prototype) {
		return;
	}
	document.addEventListener('click', function (event) {
		var button = event.target.closest('button[commandfor]');
		var dialog = button && document.getElementById(button.getAttribute('commandfor'));
		if (!(dialog instanceof HTMLDialogElement)) {
			return;
		}
		var command = button.getAttribute('command');
		if (command === 'show-modal' && !dialog.open) {
			dialog.showModal();
		} else if (command === 'close' && dialog.open) {
			dialog.close();
		}
	});
})();
