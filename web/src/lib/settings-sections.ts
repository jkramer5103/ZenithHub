import { Layers, Server, SlidersHorizontal, UserRound, Users } from '@lucide/svelte';

/** The pages under Settings: what isn't needed every day. */
export const settingsSections = [
	{ href: '/settings/accounts', label: 'Accounts', description: 'Minecraft accounts and their sign-ins', icon: UserRound, admin: false },
	{ href: '/settings/servers', label: 'Servers', description: 'Where instances connect to', icon: Server, admin: false },
	{ href: '/settings/users', label: 'Users', description: 'Who can sign in to ZenithHub', icon: Users, admin: true },
	{ href: '/settings/general', label: 'General', description: 'Public address, proxies and the Discord bot', icon: SlidersHorizontal, admin: true },
	{ href: '/settings/defaults', label: 'Defaults', description: 'Settings every instance starts with', icon: Layers, admin: true }
];
