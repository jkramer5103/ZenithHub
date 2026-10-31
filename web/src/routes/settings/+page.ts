import { redirect } from '@sveltejs/kit';

// phones get the list of sections; wider screens have it in the sidebar, so they open the first one
export function load({ url }) {
	if (url.searchParams.get('tab') === 'defaults') redirect(307, '/settings/defaults');
	if (matchMedia('(min-width: 768px)').matches) redirect(307, '/settings/accounts');
}
