export type Capability = 'VIEW' | 'SPECTATE' | 'PLAY' | 'CHAT' | 'CONTROL' | 'COMMANDS' | 'SETTINGS' | 'SHARE';
export type Phase = 'STOPPED' | 'STARTING' | 'RUNNING' | 'STOPPING' | 'BACKOFF';
export type Autostart = 'RESTORE' | 'ALWAYS' | 'NEVER';

export interface User {
	id: string;
	username: string;
	admin: boolean;
	mcNames: string[];
	discordLinked: boolean;
}

export interface Session {
	setupRequired: boolean;
	user: User | null;
}

/** Live status reported by a running instance's control API. */
export interface InstanceStatus {
	connected: boolean;
	inQueue: boolean;
	online: boolean;
	queuePosition: number;
	queueEtaSeconds: number;
	prio: boolean;
	username: string;
	server: string;
	uptimeSeconds: number;
	onlineSeconds: number;
	ping: number;
	tps: number;
	health: number;
	food: number;
	position: { x: number; y: number; z: number; dimension: string } | null;
	controller: string | null;
	spectators: string[];
	lastDisconnectReason?: string;
	lastDisconnectAt?: number;
}

export interface Runtime {
	phase: Phase;
	pid: number | null;
	startedAt: number | null;
	restartAt: number | null;
	lastError: string | null;
	status: InstanceStatus | null;
}

export interface Instance {
	id: string;
	name: string;
	accountId: string;
	accountName: string;
	serverId: string;
	autostart: Autostart;
	desiredRunning: boolean;
	capabilities: Capability[];
	runtime: Runtime;
	ownerId: string | null;
	ownerName: string | null;
	/** the viewer owns the account: full access */
	mine: boolean;
	/** someone gave the viewer access through sharing */
	sharedWithMe: boolean;
}

export interface Server {
	id: string;
	name: string;
	address: string;
	port: number;
	/** added by the admin, usable by everyone */
	shared: boolean;
	ownerName: string | null;
	mine: boolean;
	/** new instances may use it */
	usable: boolean;
	/** the viewer may edit or delete it */
	editable: boolean;
}

export interface Account {
	id: string;
	type: 'MSA' | 'OFFLINE';
	username: string;
	uuid: string | null;
	authError: string | null;
	addedAt: number;
	ownerId: string | null;
	ownerName: string | null;
	mine: boolean;
}

export interface ManagerSettings {
	publicAddress: string;
	outboundProxiesEnabled: boolean;
}

export interface Overview {
	user: User;
	/** the central Discord bot is running, so accounts can be linked */
	discordAvailable: boolean;
	/** where players join in Minecraft */
	publicAddress: string;
	instances: Instance[];
	servers: Server[];
	/** your own accounts; the admin sees everyone's */
	accounts: Account[];
	/** admin only */
	settings?: ManagerSettings;
}

export interface DiscordStatus {
	configured: boolean;
	enabled: boolean;
	phase: 'OFF' | 'CONNECTING' | 'ONLINE' | 'ERROR';
	error: string | null;
	botName?: string;
	inviteUrl?: string;
	serverName?: string | null;
	missingPermissions?: string[];
}

export interface LogLine {
	seq: number;
	ts: number;
	logger: string;
	level: string;
	text: string;
	ansi: string;
}

export type LoginState = 'WAITING_FOR_CODE' | 'WAITING_FOR_USER' | 'DONE' | 'FAILED' | 'CANCELLED';

export interface PendingLogin {
	id: string;
	state: LoginState;
	userCode: string | null;
	verificationUri: string | null;
	directVerificationUri: string | null;
	expiresAt: number;
	ownerId?: string | null;
	accountId: string | null;
	error: string | null;
}

export interface Embed {
	title: string | null;
	description: string | null;
	color?: number;
	fields: { name: string; value: string; inline: boolean }[];
}

export interface CommandResult {
	embed: Embed | null;
	lines: string[];
}

export type SettingType =
	| { type: 'boolean' | 'integer' | 'number' | 'string' | 'player' }
	| { type: 'enum'; values: string[] }
	| { type: 'list'; items: SettingType }
	| { type: 'object'; fields: Record<string, SettingType> };

export type SettingScope = 'global' | 'server' | 'instance';
export type SettingSource = 'default' | 'global' | 'server' | 'instance' | 'forced';

export interface PlayerEntry {
	username: string;
	uuid: string;
	lastRefreshed?: number;
}

/** One layer of Zenith settings, as served by /settings endpoints. Keys are dotted config paths. */
export interface SettingsView {
	scope: SettingScope;
	/** the instance is running and changes apply immediately */
	live: boolean;
	schema: Record<string, SettingType>;
	values: Record<string, unknown>;
	inherited: Record<string, unknown>;
	overrides: Record<string, unknown>;
	sources: Record<string, SettingSource>;
	forced: string[];
}

export type Role = 'VIEWER' | 'SPECTATOR' | 'PLAYER' | 'OPERATOR' | 'ADMIN';

/** A web login, as the admin sees it on the Users page. */
export interface ManagedUser {
	id: string;
	username: string;
	admin: boolean;
	mcNames: string[];
	discordId: string | null;
	createdAt: number;
	instanceCount: number;
	accountCount: number;
}

export interface Grant {
	/** user:<id>, mc:<name> or discord:<id> */
	subject: string;
	label: string;
	role: Role | null;
	extra: Capability[];
	capabilities: Capability[];
	grantedBy: string | null;
	/** the viewer holds every capability of this grant, so they may change or remove it */
	editable: boolean;
}

export interface AccessView {
	/** username of the account's owner, who always has full access */
	owner: string | null;
	grants: Grant[];
	myCapabilities: Capability[];
	users: { id: string; username: string }[];
}
