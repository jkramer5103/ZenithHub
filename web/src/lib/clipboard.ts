/**
 * Copies text. navigator.clipboard only exists on HTTPS and localhost, and the
 * panel is often opened over plain http://<ip>, so fall back to execCommand.
 */
export async function copyText(text: string): Promise<boolean> {
	try {
		if (navigator.clipboard && window.isSecureContext) {
			await navigator.clipboard.writeText(text);
			return true;
		}
	} catch {
		// fall through to the legacy way
	}
	const area = document.createElement('textarea');
	area.value = text;
	area.setAttribute('readonly', '');
	area.style.position = 'fixed';
	area.style.opacity = '0';
	area.style.pointerEvents = 'none';
	// inside the open dialog, if any: a modal dialog makes everything else inert, which blocks the copy
	(document.querySelector('dialog[open]') ?? document.body).appendChild(area);
	area.select();
	area.setSelectionRange(0, text.length);
	let ok = false;
	try {
		ok = document.execCommand('copy');
	} catch {
		ok = false;
	}
	area.remove();
	return ok;
}
