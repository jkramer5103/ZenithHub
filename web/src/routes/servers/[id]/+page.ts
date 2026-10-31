import { redirect } from '@sveltejs/kit';

// moved under Settings; old bookmarks still work
export function load({ params }) {
	redirect(307, `/settings/servers/${params.id}`);
}
