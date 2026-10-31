import { redirect } from '@sveltejs/kit';

// moved under Settings; old bookmarks still work
export function load() {
	redirect(307, '/settings/accounts');
}
