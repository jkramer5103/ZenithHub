import { Layers, Plug, Puzzle, Server, SlidersHorizontal, UserRound, Users } from '@lucide/svelte';
import { pageHref, plugins } from './plugins.svelte';

/** The pages under Settings: what isn't needed every day. */
export const settingsSections = [
	{ href: '/settings/accounts', label: 'Accounts', description: 'Minecraft accounts and their sign-ins', icon: UserRound, admin: false },
	{ href: '/settings/servers', label: 'Servers', description: 'Where instances connect to', icon: Server, admin: false },
	{
		href: '/settings/integrations',
		label: 'Integrations',
		description: 'Tokens that let other programs, like Blockbot, use your bots',
		icon: Plug,
		admin: false
	},
	{ href: '/settings/users', label: 'Users', description: 'Who can sign in to ZenithHub', icon: Users, admin: true },
	{ href: '/settings/general', label: 'General', description: 'Public address, proxies and the Discord bot', icon: SlidersHorizontal, admin: true },
	{ href: '/settings/defaults', label: 'Defaults', description: 'Settings every instance starts with', icon: Layers, admin: true },
	{ href: '/settings/plugins', label: 'Plugins', description: 'Add features to ZenithHub with plugin zips', icon: Puzzle, admin: true }
];

export interface SettingsSection {
	href: string;
	label: string;
	description: string;
	icon?: (typeof settingsSections)[number]['icon'];
	/** a plugin's SVG icon */
	svg?: string;
	admin: boolean;
}

/** The built-in sections and the ones plugins add, for the current user. Reactive. */
export function visibleSections(isAdmin: boolean): SettingsSection[] {
	const extra: SettingsSection[] = plugins.settingsPages.map((p) => ({
		href: pageHref(p, true),
		label: p.title,
		description: p.description ?? '',
		svg: p.icon,
		admin: !!p.admin
	}));
	return [...settingsSections, ...extra].filter((s) => !s.admin || isAdmin);
}
