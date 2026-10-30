import { redirect } from '@sveltejs/kit';

// without a matching route SvelteKit reloads the page to let the server handle it, and the server hands back
// this same app, so an unknown path (an old bookmark like /login) reloaded forever
export function load() {
	redirect(307, '/');
}
