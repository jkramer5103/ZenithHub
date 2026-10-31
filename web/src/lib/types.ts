export type Capability = 'VIEW' | 'SPECTATE' | 'PLAY' | 'CHAT' | 'PEARLS' | 'CONTROL' | 'COMMANDS' | 'SETTINGS' | 'SHARE';
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
	/** only while in a queue with a known position */
	queueEtaSeconds?: number;
	prio: boolean;
	username: string;
	server: string;
	uptimeSeconds: number;
	onlineSeconds: number;
	ping: number;
	tps: number;
	/** health, food and position only while online, i.e. in the game */
	health?: number;
	food?: number;
	// dimension can still be null right after joining, before the world loads
	position?: { x: number; y: number; z: number; dimension: string | null } | null;
	controller: string | null;
	spectators: string[];
	lastDisconnectReason?: string;
	lastDisconnectAt?: number;
	/** only while the AFK zone is in use: kept in it, in it, or a reward is on the sidebar */
	afkZone?: AfkZoneStatus;
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
	/** Set for a pasted access token, which can't be renewed: when it stops working. */
	tokenExpiresAt?: number;
	addedAt: number;
	ownerId: string | null;
	ownerName: string | null;
	mine: boolean;
}

export interface ManagerSettings {
	publicAddress: string;
	/** users may set their own proxy; see ProxyCard */
	userProxiesAllowed: boolean;
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
	/** the command reported a failure */
	error?: boolean;
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
	/** ZENITH_DISABLE_CHEATS is set: cheat modules are off and left out of the schema */
	cheatsDisabled: boolean;
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

export interface ItemStack {
	/** registry name without the minecraft: prefix, e.g. "diamond_sword" */
	id: string;
	count: number;
	/** set with an anvil */
	name?: string;
}

export interface Waypoint {
	id: string;
	dimension: string;
	x: number;
	y: number;
	z: number;
}

/** The open container, or the player inventory, with Zenith's slot numbers. */
export interface Inventory {
	containerId: number;
	title: string | null;
	size: number;
	/** first hotbar slot: 36 in the player inventory, else size - 9 */
	hotbarStart: number;
	/** the held hotbar slot, as a slot number */
	heldSlot: number;
	slots: (ItemStack | null)[];
}

export type TaskEvent = 'connect' | 'death' | 'disconnect' | 'online' | 'playerConnect' | 'playerDisconnect';

export interface ScheduledTask {
	id: string;
	type: 'timed' | 'interval' | 'event' | 'trigger' | 'other';
	intervalSeconds?: number;
	/** the bot's trigger spec, like "player;who=stranger;cooldown=60" */
	trigger?: string;
	/** epoch ms of the next run, for timed, interval and daily tasks */
	next?: number;
	event?: string;
	repeat?: boolean;
	/** missing for tasks whose action isn't a command */
	command?: string;
}

/** Live details for the Control and Tasks tabs, polled while they're open. */
export interface ConnectedPlayer {
	name: string;
	uuid: string | null;
	spectator: boolean;
	/** on the account's whitelist, so a swap lets them play */
	canPlay: boolean;
	/** new enough (1.20.5+) to be swapped with a transfer */
	canSwap: boolean;
	version: string;
}

/** another player the bot can see */
export interface NearbyPlayer {
	/** from the tab list; null if the server hides it */
	name: string | null;
	uuid: string;
	x: number;
	y: number;
	z: number;
	distance: number;
	friend: boolean;
}

export interface HistoryEntry {
	ts: number;
	event: 'joined' | 'left' | 'switched' | 'kicked';
	name: string;
	uuid?: string;
	/** the role after the event */
	spectator: boolean;
	reason?: string;
	/** who kicked or swapped them, from the web UI */
	actor?: string;
}

export interface InstanceDetails {
	running: boolean;
	online: boolean;
	alive?: boolean;
	controlled?: boolean;
	/** everyone connected through the proxy, the controlling player first */
	players?: ConnectedPlayer[];
	/** only while the bot is in the game, closest first */
	nearby?: NearbyPlayer[];
	inventory?: Inventory;
	pathfinder?: { active: boolean; process: string | null };
	recording?: boolean;
	/** a held mouse button, while the bot keeps clicking */
	clicking?: 'left' | 'right';
	tasks: ScheduledTask[];
	/** the Tasks module is on; tasks only run when it is */
	tasksEnabled?: boolean;
	waypoints: Waypoint[];
	/** what the server can see of the bot's client, while it's connected */
	fingerprint?: Fingerprint;
	/** what the server shows besides chat, while the bot is in the game */
	screen?: ServerScreen;
	afkZone?: AfkZoneStatus;
}

export interface ServerScreen {
	sidebar?: { title: string; lines: string[] };
	bossBars: { title: string; progress: number; color: string | null }[];
	actionBar?: string | null;
	title?: string;
	subtitle?: string;
	tabHeader?: string;
	tabFooter?: string;
}

export type AfkZoneState = 'OFFLINE' | 'OUTSIDE' | 'WAITING' | 'ENTERING' | 'IN_ZONE' | 'PLAYING' | 'FAILED';

export interface AfkZoneStatus {
	state: AfkZoneState;
	/** go back in whenever it's outside */
	keep: boolean;
	enterCommand: string;
	leaveCommand: string;
	rewardLabel: string;
	/** in the zone since */
	since?: number;
	nextAttemptAt?: number;
	sentAt?: number;
	attempts: number;
	maxAttempts: number;
	failure?: string;
	leftAt?: number;
	leftReason?: string;
	/** absent while it can't tell yet */
	earning?: boolean;
	/** the zone's last action bar or title text */
	serverMessage?: string;
	/** the message says it earns nothing */
	serverMessageBlocks?: boolean;
	reward?: number;
	rewardText?: string;
	rewardAtEntry?: number;
}

/** What each instance earned in the AFK zone, by instance id; only instances with a history. */
/** what each instance's counter rose by: the last day and week, and ever since ZenithHub first saw it */
export type AfkSummary = Record<string, { day: number | null; week: number | null; lifetime: number }>;

export interface AfkHistory {
	/** [timestamp, reward], oldest first */
	samples: [number, number][];
	/** newest first */
	sessions: { start: number; end?: number; startReward?: number; endReward?: number; endReason?: string }[];
}

export interface Fingerprint {
	brand: string;
	clientVersion: string;
	serverVersion: string;
	locale: string;
	viewDistance: number;
	mainHand: 'left' | 'right';
	allowsListing: boolean;
	chatSigning: 'signed' | 'unavailable' | 'off';
	ping: number;
	pingSource: 'tablist' | 'requests';
	tickAlignedReplies: boolean;
	knownPacks: boolean;
	cookies: number;
	resourcePacks: number;
	downloadsResourcePacks: boolean;
}

export interface Pearl {
	id: string;
	x: number;
	y: number;
	z: number;
	/** the viewer has a webhook URL for it */
	hasWebhook: boolean;
}

export interface Replay {
	name: string;
	size: number;
	modified: number;
}

export interface ProxyPoolStatus {
	enabled: boolean;
	type: 'SOCKS5' | 'SOCKS4' | 'HTTP';
	/** a list URL is saved; it's never sent back */
	hasUrl: boolean;
	size: number;
	free: number;
	refreshedAt?: number;
	error?: string;
	accounts: {
		accountId: string;
		username: string;
		proxy?: string;
		inPool?: boolean;
		/** the admin's proxy for this account, over the pool; never the password */
		override?: { type: 'DIRECT' | 'SOCKS5' | 'SOCKS4' | 'HTTP'; host?: string; port?: number; user?: string; hasPassword?: boolean };
		/** every instance of it joins a server on the local network, so it connects directly */
		lan?: boolean;
	}[];
}
