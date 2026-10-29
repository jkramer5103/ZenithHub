import type { Capability, Role } from './types';

/** What each role means, in the words the UI uses. Mirrors the manager's Role enum. */
export const roles: { id: Role; label: string; description: string; capabilities: Capability[] }[] = [
	{ id: 'VIEWER', label: 'Viewer', description: 'Sees status and chat', capabilities: ['VIEW'] },
	{ id: 'SPECTATOR', label: 'Spectator', description: 'Can also watch in-game', capabilities: ['VIEW', 'SPECTATE'] },
	{ id: 'PLAYER', label: 'Player', description: 'Can also play and chat as the account', capabilities: ['VIEW', 'SPECTATE', 'PLAY', 'CHAT'] },
	{
		id: 'OPERATOR',
		label: 'Operator',
		description: 'Can also start, stop and run commands',
		capabilities: ['VIEW', 'SPECTATE', 'PLAY', 'CHAT', 'CONTROL', 'COMMANDS']
	},
	{
		id: 'ADMIN',
		label: 'Co-owner',
		description: 'Everything, including settings and sharing',
		capabilities: ['VIEW', 'SPECTATE', 'PLAY', 'CHAT', 'CONTROL', 'COMMANDS', 'SETTINGS', 'SHARE']
	}
];

export const capabilityLabels: Record<Capability, string> = {
	VIEW: 'See status and chat',
	SPECTATE: 'Watch in-game',
	PLAY: 'Play in-game',
	CHAT: 'Send chat messages',
	CONTROL: 'Start, stop and connect',
	COMMANDS: 'Run commands',
	SETTINGS: 'Change settings',
	SHARE: 'Share with others'
};

export const allCapabilities = Object.keys(capabilityLabels) as Capability[];

export function roleOf(id: Role | null) {
	return roles.find((r) => r.id === id) ?? roles[0];
}
