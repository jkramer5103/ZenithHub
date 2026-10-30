import type { Component } from 'svelte';
import type { SettingScope } from './types';
import {
	Bell,
	Bot,
	MapPin,
	Cable,
	EyeOff,
	Heart,
	MessageSquare,
	Network,
	Shield,
	Sparkles,
	Swords,
	Users,
	Wrench
} from '@lucide/svelte';

/** One setting in the curated settings UI. */
export interface Field {
	path: string;
	label: string;
	help?: string;
	/** only shown while this boolean setting is on, or while the predicate holds */
	showIf?: string | ((values: Record<string, unknown>) => boolean);
	/** rarely changed: only shown with "Show advanced settings" on, or in search results */
	advanced?: boolean;
	unit?: string;
	min?: number;
	max?: number;
	/** friendlier labels for enum values */
	options?: Record<string, string>;
	placeholder?: string;
	secret?: boolean;
	/** long text: rendered as a textarea */
	multiline?: boolean;
	/** lists of records: how to show each record field */
	columns?: Record<string, Column>;
	/** label of the add button for lists of records */
	addLabel?: string;
}

/** One field of a record in a list, like a waypoint's X coordinate. */
export interface Column {
	label?: string;
	/** CSS grid track, e.g. '5rem' */
	width?: string;
	min?: number;
	max?: number;
	placeholder?: string;
	/** pick from these values instead of typing */
	options?: Record<string, string>;
	default?: unknown;
	/** no two rows may share this value */
	unique?: boolean;
	optional?: boolean;
}

const COORD = { width: '6rem', default: 0 };

export interface Section {
	id: string;
	title: string;
	description: string;
	/** boolean setting shown as the section's main switch; fields hide while it's off */
	toggle?: string;
	/** the whole section, switch included, is only shown with "Show advanced settings" on */
	advanced?: boolean;
	/** only shown on these settings pages */
	scopes?: SettingScope[];
	fields: Field[];
}

export interface Group {
	id: string;
	title: string;
	icon: Component;
	sections: Section[];
}

const TICKS = 'ticks (20 = 1s)';

/** A module's input priority. Zenith's built-in default applies until it's set. */
function priority(path: string, fallback: number): Field {
	return {
		path,
		label: 'Input priority',
		placeholder: String(fallback),
		advanced: true,
		help: `When modules want to move, look or click at the same time, the higher number wins. Default ${fallback}.`
	};
}

const isMode = (path: string, ...modes: string[]) => (v: Record<string, unknown>) => modes.includes(v[path] as string);

/** one direction of the packet log */
function packetLog(side: 'client' | 'server', who: string): Field[] {
	const p = `debug.packetLog.${side}PacketLog`;
	return [
		{ path: `${p}.received`, label: `${who}: received packets` },
		{ path: `${p}.receivedBody`, label: `${who}: received packet contents`, showIf: `${p}.received` },
		{ path: `${p}.preSent`, label: `${who}: packets about to be sent` },
		{ path: `${p}.preSentBody`, label: `${who}: contents of packets about to be sent`, showIf: `${p}.preSent` },
		{ path: `${p}.postSent`, label: `${who}: sent packets` },
		{ path: `${p}.postSentBody`, label: `${who}: sent packet contents`, showIf: `${p}.postSent` }
	];
}

export const catalog: Group[] = [
	{
		id: 'connection',
		title: 'Connection',
		icon: Cable,
		sections: [
			{
				id: 'autoReconnect',
				title: 'Auto reconnect',
				description: 'Reconnect after being kicked or losing the connection.',
				toggle: 'client.extra.autoReconnect.enabled',
				fields: [
					{ path: 'client.extra.autoReconnect.delaySeconds', label: 'Wait before reconnecting', unit: 'seconds', min: 0 },
					{
						path: 'client.extra.autoReconnect.randomSeconds',
						label: 'Randomize the wait by ±',
						unit: 'seconds',
						min: 0,
						help: 'Each reconnect waits a random time this much shorter or longer, so it doesn’t look like a bot. 0 waits exactly the time above.'
					},
					{ path: 'client.extra.autoReconnect.maxAttempts', label: 'Give up after', unit: 'attempts', min: 1 }
				]
			},
			{
				id: 'maxPlaytime',
				title: 'Scheduled reconnect',
				description: 'Reconnect after a fixed session length, e.g. to stay ahead of server session limits.',
				toggle: 'client.maxPlaytimeReconnect',
				fields: [
					{ path: 'client.maxPlaytimeReconnectMins', label: 'Reconnect every', unit: 'minutes', min: 1 },
					{
						path: 'client.maxPlaytimeReconnectRandomMins',
						label: 'Randomize by ±',
						unit: 'minutes',
						min: 0,
						help: 'Each session lasts a random time this much shorter or longer. 0 reconnects at exactly the interval above.'
					}
				]
			},
			{
				id: 'activeHours',
				title: 'Active hours',
				description: 'Connect at set times of day. Queue time is accounted for, so you’re through the queue on time.',
				toggle: 'client.extra.utility.actions.activeHours.enabled',
				fields: [
					{ path: 'client.extra.utility.actions.activeHours.activeTimes', label: 'Be online at' },
					{
						path: 'client.extra.utility.actions.activeHours.timeZoneId',
						label: 'Time zone',
						placeholder: 'Europe/Berlin',
						help: 'A time zone name like Europe/Berlin or America/New_York.'
					},
					{ path: 'client.extra.utility.actions.activeHours.queueEtaCalc', label: 'Join early to account for the queue' },
					{ path: 'client.extra.utility.actions.activeHours.forceReconnect', label: 'Reconnect even if already online' },
					{
						path: 'client.extra.utility.actions.activeHours.fullSessionUntilNextDisconnect',
						label: 'Skip if the session would end first',
						help: 'Don’t reconnect if the server’s playtime limit would cut the session short before the next active time.'
					}
				]
			},
			{
				id: 'sessionTimeLimit',
				title: 'Session time limit warnings',
				description: 'Warn before 2b2t kicks the account for reaching its session time limit.',
				toggle: 'client.extra.sessionTimeLimit.enabled',
				fields: [
					{
						path: 'client.extra.sessionTimeLimit.ingameNotificationPositions',
						label: 'Warn in-game at',
						placeholder: 'minutes left',
						help: 'Minutes left in the session.'
					},
					{
						path: 'client.extra.sessionTimeLimit.discordNotificationPositions',
						label: 'Post to Discord at',
						placeholder: 'minutes left',
						help: 'Minutes left in the session.'
					},
					{
						path: 'client.extra.sessionTimeLimit.discordMentionPositions',
						label: 'Ping on Discord at',
						placeholder: 'minutes left',
						help: 'Minutes left in the session.'
					},
					{
						path: 'client.extra.sessionTimeLimit.dynamic2b2tSessionTimeLimit',
						label: 'Read the limit from 2b2t',
						help: 'Use the session length 2b2t announces instead of a fixed one.',
						advanced: true
					}
				]
			},
			{
				id: 'proxy',
				title: 'Outbound proxy',
				description:
					'Send everything through a SOCKS or HTTP proxy instead of this machine’s IP: the server connection, Microsoft and Xbox sign-in, the session server, skins and every other request.',
				toggle: 'client.connectionProxy.enabled',
				// the global layer's proxy is the global proxy, set up under Settings → General
				scopes: ['server', 'instance'],
				fields: [
					{ path: 'client.connectionProxy.type', label: 'Type' },
					{ path: 'client.connectionProxy.host', label: 'Host', placeholder: 'proxy.example.com' },
					{ path: 'client.connectionProxy.port', label: 'Port', min: 1, max: 65535 },
					{ path: 'client.connectionProxy.user', label: 'Username', placeholder: 'optional' },
					{ path: 'client.connectionProxy.password', label: 'Password', secret: true, placeholder: 'optional' }
				]
			},
			{
				id: 'viaversion',
				title: 'Version translation',
				description: 'Join servers on any Minecraft version, using the newest one the server supports (ViaVersion).',
				toggle: 'client.viaversion.enabled',
				fields: [
					{ path: 'client.viaversion.autoProtocolVersion', label: 'Detect the server version automatically' },
					{
						path: 'client.viaversion.protocolVersion',
						label: 'Protocol version',
						help: 'Only used when automatic detection is off.',
						showIf: (v) => v['client.viaversion.autoProtocolVersion'] === false
					},
					{ path: 'client.viaversion.disableOn2b2t', label: 'Never use on 2b2t' }
				]
			}
		]
	},
	{
		id: 'survival',
		title: 'Survival',
		icon: Heart,
		sections: [
			{
				id: 'autoEat',
				title: 'Auto eat',
				description: 'Eat when health or hunger drops low.',
				toggle: 'client.extra.autoEat.enabled',
				fields: [
					{ path: 'client.extra.autoEat.healthThreshold', label: 'Eat below health', unit: 'of 20', min: 0, max: 20 },
					{ path: 'client.extra.autoEat.hungerThreshold', label: 'Eat below hunger', unit: 'of 20', min: 0, max: 20 },
					{
						path: 'client.extra.autoEat.mode',
						label: 'Foods to eat',
						options: { ALL: 'Any food', WHITELIST: 'Only the foods listed', BLACKLIST: 'Anything except the foods listed' }
					},
					{
						path: 'client.extra.autoEat.foods',
						label: 'Food list',
						placeholder: 'golden_carrot',
						help: 'Item IDs, e.g. golden_carrot or cooked_beef.',
						showIf: (v) => v['client.extra.autoEat.mode'] !== 'ALL'
					},
					{ path: 'client.extra.autoEat.allowUnsafeFood', label: 'Allow food with bad effects', help: 'Rotten flesh, spider eyes, pufferfish…' },
					{ path: 'client.extra.autoEat.warning', label: 'Warn when out of food' },
					priority('client.extra.autoEat.priority', 11000)
				]
			},
			{
				id: 'autoTotem',
				title: 'Auto totem',
				description: 'Keep a totem of undying in the offhand.',
				toggle: 'client.extra.autoTotem.enabled',
				fields: [
					{ path: 'client.extra.autoTotem.healthThreshold', label: 'Equip below health', unit: 'of 20', min: 0, max: 20 },
					{ path: 'client.extra.autoTotem.inGame', label: 'Also while you’re playing' },
					{ path: 'client.extra.autoTotem.totemPopAlert', label: 'Alert when a totem pops' },
					{ path: 'client.extra.autoTotem.noTotemsAlert', label: 'Alert when out of totems' },
					priority('client.extra.autoTotem.priority', 13000)
				]
			},
			{
				id: 'autoRespawn',
				title: 'Auto respawn',
				description: 'Respawn right after dying.',
				toggle: 'client.extra.autoRespawn.enabled',
				fields: [{ path: 'client.extra.autoRespawn.delayMillis', label: 'Delay', unit: 'ms', min: 0 }]
			},
			{
				id: 'autoArmor',
				title: 'Auto armor',
				description: 'Put on the best armor in the inventory.',
				toggle: 'client.extra.autoArmor.enabled',
				fields: [priority('client.extra.autoArmor.priority', 12000)]
			},
			{
				id: 'autoMend',
				title: 'Auto mend',
				description: 'Swap damaged Mending gear into the hands to repair it with XP.',
				toggle: 'client.extra.autoMend.enabled',
				fields: [priority('client.extra.autoMend.priority', 4000)]
			},
			{
				id: 'autoOmen',
				title: 'Auto omen',
				description: 'Drink Bad Omen potions from the inventory, e.g. for raid farms.',
				toggle: 'client.extra.autoOmen.enabled',
				fields: [
					{
						path: 'client.extra.autoOmen.mode',
						label: 'Drink when',
						options: {
							EFFECT_AND_RAID_INACTIVE: 'No omen effect and no raid',
							EFFECT_INACTIVE: 'No omen effect',
							CONSTANT: 'On a fixed timer'
						}
					},
					{
						path: 'client.extra.autoOmen.constantTicks',
						label: 'Drink every',
						unit: TICKS,
						min: 1,
						help: 'The default matches the omen effect’s length, 100 seconds.',
						showIf: isMode('client.extra.autoOmen.mode', 'CONSTANT')
					},
					{
						path: 'client.extra.autoOmen.consumeFullOmenStack',
						label: 'Drink the last potion of a stack',
						help: 'Off keeps one potion in each stack.'
					},
					priority('client.extra.autoOmen.priority', 10000)
				]
			}
		]
	},
	{
		id: 'safety',
		title: 'Safety',
		icon: Shield,
		sections: [
			{
				id: 'autoDisconnect',
				title: 'Auto disconnect',
				description: 'Log out automatically when the account is in danger.',
				toggle: 'client.extra.utility.actions.autoDisconnect.enabled',
				fields: [
					{ path: 'client.extra.utility.actions.autoDisconnect.healthDisconnect', label: 'On low health' },
					{
						path: 'client.extra.utility.actions.autoDisconnect.health',
						label: 'Health limit',
						unit: 'of 20',
						min: 1,
						max: 20,
						showIf: 'client.extra.utility.actions.autoDisconnect.healthDisconnect'
					},
					{ path: 'client.extra.utility.actions.autoDisconnect.onTotemPop', label: 'When a totem pops' },
					{
						path: 'client.extra.utility.actions.autoDisconnect.minTotemsRemaining',
						label: 'Only below',
						unit: 'totems left',
						min: 0,
						showIf: 'client.extra.utility.actions.autoDisconnect.onTotemPop'
					},
					{
						path: 'client.extra.utility.actions.autoDisconnect.onUnknownPlayerInVisualRange',
						label: 'When a stranger comes into view',
						help: 'Anyone who isn’t on the friends list.'
					},
					{ path: 'client.extra.utility.actions.autoDisconnect.thunder', label: 'During thunderstorms' },
					{
						path: 'client.extra.utility.actions.autoDisconnect.autoClientDisconnect',
						label: 'When you stop playing',
						help: 'Log the account out as soon as you leave the proxy.'
					},
					{ path: 'client.extra.utility.actions.autoDisconnect.whilePlayerConnected', label: 'Also while you’re playing' },
					{ path: 'client.extra.utility.actions.autoDisconnect.cancelAutoReconnect', label: 'Stay offline afterwards', help: 'Don’t auto reconnect.' }
				]
			},
			{
				id: 'visualRange',
				title: 'Visual range alerts',
				description: 'Notice players entering and leaving render distance.',
				toggle: 'client.extra.visualRange.enabled',
				fields: [
					{ path: 'client.extra.visualRange.enterAlert', label: 'Alert when someone appears' },
					{ path: 'client.extra.visualRange.leaveAlert', label: 'Alert when someone leaves' },
					{ path: 'client.extra.visualRange.logoutAlert', label: 'Alert when someone logs out nearby' },
					{ path: 'client.extra.visualRange.ignoreFriends', label: 'Ignore friends' },
					{ path: 'client.extra.visualRange.enterWhisper', label: 'Whisper players who appear' },
					{
						path: 'client.extra.visualRange.enterWhisperMessage',
						label: 'Whisper message',
						multiline: true,
						showIf: 'client.extra.visualRange.enterWhisper'
					},
					{
						path: 'client.extra.visualRange.enterWhisperCooldownSeconds',
						label: 'Whisper cooldown',
						unit: 'seconds',
						min: 0,
						showIf: 'client.extra.visualRange.enterWhisper'
					},
					{
						path: 'client.extra.visualRange.enterWhisperWhilePlayerConnected',
						label: 'Also whisper while you’re playing',
						showIf: 'client.extra.visualRange.enterWhisper'
					}
				]
			},
			{
				id: 'stalk',
				title: 'Watched players',
				description: 'Get alerted when certain players join or leave the server.',
				toggle: 'client.extra.stalk.enabled',
				fields: [{ path: 'client.extra.stalk.stalking', label: 'Players' }]
			},
			{
				id: 'antiLeak',
				title: 'Coordinate leak protection',
				description: 'Block chat messages that contain your coordinates.',
				toggle: 'client.extra.antiLeak.enabled',
				fields: [
					{ path: 'client.extra.antiLeak.rangeCheck', label: 'Only numbers close to your position' },
					{ path: 'client.extra.antiLeak.rangeFactor', label: 'Range factor', min: 1, showIf: 'client.extra.antiLeak.rangeCheck' }
				]
			},
			{
				id: 'coordObfuscation',
				title: 'Coordinate obfuscation',
				description: 'Show spectators and controllers fake coordinates.',
				toggle: 'client.extra.coordObfuscation.enabled',
				fields: [
					{
						path: 'client.extra.coordObfuscation.mode',
						label: 'Mode',
						options: { RANDOM_OFFSET: 'Random offset', CONSTANT_OFFSET: 'Constant offset', AT_LOCATION: 'Pretend to be at a location' }
					},
					{
						path: 'client.extra.coordObfuscation.constantOffsetX',
						label: 'Offset X',
						showIf: (v) => v['client.extra.coordObfuscation.mode'] === 'CONSTANT_OFFSET'
					},
					{
						path: 'client.extra.coordObfuscation.constantOffsetZ',
						label: 'Offset Z',
						showIf: (v) => v['client.extra.coordObfuscation.mode'] === 'CONSTANT_OFFSET'
					},
					{
						path: 'client.extra.coordObfuscation.atLocationX',
						label: 'Location X',
						showIf: (v) => v['client.extra.coordObfuscation.mode'] === 'AT_LOCATION'
					},
					{
						path: 'client.extra.coordObfuscation.atLocationZ',
						label: 'Location Z',
						showIf: (v) => v['client.extra.coordObfuscation.mode'] === 'AT_LOCATION'
					},
					{ path: 'client.extra.coordObfuscation.exemptProxyAccount', label: 'Show real coordinates to the account owner' },
					{
						path: 'client.extra.coordObfuscation.randomMinDistanceFromSelf',
						label: 'Fake position at least this far from the real one',
						unit: 'blocks',
						min: 0,
						advanced: true,
						showIf: isMode('client.extra.coordObfuscation.mode', 'RANDOM_OFFSET')
					},
					{
						path: 'client.extra.coordObfuscation.randomMinDistanceFromSpawn',
						label: 'Fake position at least this far from spawn',
						unit: 'blocks',
						min: 0,
						advanced: true,
						showIf: isMode('client.extra.coordObfuscation.mode', 'RANDOM_OFFSET')
					},
					{
						path: 'client.extra.coordObfuscation.randomMaxDistanceFromSpawn',
						label: 'Fake position at most this far from spawn',
						unit: 'blocks',
						min: 0,
						advanced: true,
						showIf: isMode('client.extra.coordObfuscation.mode', 'RANDOM_OFFSET')
					},
					{
						path: 'client.extra.coordObfuscation.constantOffsetNetherTranslate',
						label: 'Scale the offset in the Nether',
						help: 'Divides it by 8, so portals line up with the fake Overworld coordinates.',
						advanced: true,
						showIf: isMode('client.extra.coordObfuscation.mode', 'CONSTANT_OFFSET')
					},
					{
						path: 'client.extra.coordObfuscation.constantOffsetMinSpawnDistance',
						label: 'Disconnect players closer to spawn than',
						unit: 'blocks',
						min: 0,
						help: 'Near spawn, landmarks could give the offset away.',
						advanced: true,
						showIf: isMode('client.extra.coordObfuscation.mode', 'CONSTANT_OFFSET')
					},
					{
						path: 'client.extra.coordObfuscation.teleportOffsetRegenerateDistanceMin',
						label: 'New offset after teleporting at least',
						unit: 'blocks',
						min: 0,
						advanced: true
					},
					{ path: 'client.extra.coordObfuscation.obfuscateBedrock', label: 'Hide bedrock patterns', advanced: true },
					{ path: 'client.extra.coordObfuscation.obfuscateChunkHeightmap', label: 'Hide chunk heightmaps', advanced: true },
					{ path: 'client.extra.coordObfuscation.obfuscateChunkLighting', label: 'Hide chunk lighting', advanced: true },
					{ path: 'client.extra.coordObfuscation.obfuscateBiomes', label: 'Hide biomes', advanced: true },
					{
						path: 'client.extra.coordObfuscation.obfuscateBiomesKey',
						label: 'Show every biome as',
						placeholder: 'plains',
						advanced: true,
						showIf: 'client.extra.coordObfuscation.obfuscateBiomes'
					},
					{
						path: 'client.extra.coordObfuscation.disconnectWhileEyeOfEnderPresent',
						label: 'Disconnect players while an eye of ender is nearby',
						help: 'Thrown eyes point at strongholds, which reveals real coordinates.',
						advanced: true
					},
					{
						path: 'client.extra.coordObfuscation.disconnectWhileNearOffsetBlocks',
						label: 'Disconnect players near position-revealing blocks',
						help: 'Some plants and dripstone are drawn shifted by their position, which can reveal real coordinates.',
						advanced: true
					},
					{
						path: 'client.extra.coordObfuscation.delayPlayerLoginsAfterTpMs',
						label: 'Hold back joins after a teleport for',
						unit: 'ms',
						min: 0,
						advanced: true
					},
					{
						path: 'client.extra.coordObfuscation.validateSetup',
						label: 'Check the setup when turning on',
						help: 'Refuses to turn on while other settings, like the action limits, could leak coordinates.',
						advanced: true
					},
					{ path: 'client.extra.coordObfuscation.debugPacketLog', label: 'Log changed packets', advanced: true }
				]
			},
			{
				id: 'actionLimiter',
				title: 'Action limits',
				description: 'Restrict what people controlling the account may do.',
				toggle: 'client.extra.actionLimiter.enabled',
				fields: [
					{ path: 'client.extra.actionLimiter.allowMovement', label: 'Allow moving' },
					{
						path: 'client.extra.actionLimiter.movementDistance',
						label: 'Max distance from home',
						unit: 'blocks',
						min: 0,
						showIf: 'client.extra.actionLimiter.allowMovement'
					},
					{ path: 'client.extra.actionLimiter.movementHomeX', label: 'Home X', showIf: 'client.extra.actionLimiter.allowMovement' },
					{ path: 'client.extra.actionLimiter.movementHomeZ', label: 'Home Z', showIf: 'client.extra.actionLimiter.allowMovement' },
					{
						path: 'client.extra.actionLimiter.movementMinY',
						label: 'Lowest Y allowed',
						help: 'Players who go below are disconnected.',
						showIf: 'client.extra.actionLimiter.allowMovement'
					},
					{ path: 'client.extra.actionLimiter.allowBlockBreaking', label: 'Allow breaking blocks' },
					{ path: 'client.extra.actionLimiter.allowInteract', label: 'Allow using blocks and entities' },
					{ path: 'client.extra.actionLimiter.allowInventory', label: 'Allow inventory changes' },
					{ path: 'client.extra.actionLimiter.allowEnderChest', label: 'Allow opening ender chests' },
					{ path: 'client.extra.actionLimiter.allowUseItem', label: 'Allow using items' },
					{ path: 'client.extra.actionLimiter.allowBookSigning', label: 'Allow signing books' },
					{ path: 'client.extra.actionLimiter.allowChat', label: 'Allow chatting' },
					{ path: 'client.extra.actionLimiter.allowServerCommands', label: 'Allow server commands' },
					{ path: 'client.extra.actionLimiter.allowRespawn', label: 'Allow respawning' },
					{ path: 'client.extra.actionLimiter.itemsBlacklistEnabled', label: 'Block certain items' },
					{
						path: 'client.extra.actionLimiter.itemsBlacklist',
						label: 'Blocked items',
						placeholder: 'ender_pearl',
						help: 'Item IDs players may not use, e.g. ender_pearl.',
						showIf: 'client.extra.actionLimiter.itemsBlacklistEnabled'
					},
					{ path: 'client.extra.actionLimiter.exemptProxyAccount', label: 'Don’t limit the account owner' }
				]
			}
		]
	},
	{
		id: 'afk',
		title: 'AFK',
		icon: Bot,
		sections: [
			{
				id: 'afkZone',
				title: 'AFK zone',
				description: 'Servers that pay for idling in a zone, like HugoSMP’s /afk. The AFK tab shows whether it pays.',
				toggle: 'client.afkZone.keep',
				fields: [
					{ path: 'client.afkZone.enterCommand', label: 'Command to get in', placeholder: 'afk', help: 'Without the slash.' },
					{ path: 'client.afkZone.leaveCommand', label: 'Command to get out', placeholder: 'spawn', help: 'Without the slash.' },
					{
						path: 'client.afkZone.confirmText',
						label: 'Server confirms with',
						help: 'Text in the title, subtitle or action bar the server shows on entering.'
					},
					{ path: 'client.afkZone.rewardLabel', label: 'Reward on the sidebar', help: 'The sidebar line with the counter, e.g. Stars.' },
					{
						path: 'client.afkZone.notEarningText',
						label: 'Not earning when it says',
						help: 'Text in the server’s zone message that means the account earns nothing, e.g. a Discord verification hint.',
						advanced: true
					},
					{ path: 'client.afkZone.enterDelayMinSeconds', label: 'Wait at least', unit: 'seconds before going in', min: 0, advanced: true },
					{ path: 'client.afkZone.enterDelayMaxSeconds', label: 'Wait at most', unit: 'seconds before going in', min: 0, advanced: true },
					{ path: 'client.afkZone.maxAttempts', label: 'Give up after', unit: 'attempts', min: 1, advanced: true },
					{ path: 'client.afkZone.leaveDistance', label: 'Counts as left after', unit: 'blocks from the zone', min: 1, advanced: true }
				]
			},
			{
				id: 'antiafk',
				title: 'Anti-AFK',
				description: 'Small movements so the server doesn’t kick the account for being idle.',
				toggle: 'client.extra.antiafk.enabled',
				fields: [
					{ path: 'client.extra.antiafk.actions.walk', label: 'Walk around' },
					{
						path: 'client.extra.antiafk.actions.walkDistance',
						label: 'Walk distance',
						unit: 'blocks',
						min: 1,
						showIf: 'client.extra.antiafk.actions.walk'
					},
					{ path: 'client.extra.antiafk.actions.safeWalk', label: 'Don’t walk off edges', showIf: 'client.extra.antiafk.actions.walk' },
					{
						path: 'client.extra.antiafk.actions.walkDelayTicks',
						label: 'Walk every',
						unit: TICKS,
						min: 1,
						advanced: true,
						showIf: 'client.extra.antiafk.actions.walk'
					},
					{ path: 'client.extra.antiafk.actions.swingHand', label: 'Swing arm' },
					{
						path: 'client.extra.antiafk.actions.swingDelayTicks',
						label: 'Swing every',
						unit: TICKS,
						min: 1,
						advanced: true,
						showIf: 'client.extra.antiafk.actions.swingHand'
					},
					{ path: 'client.extra.antiafk.actions.rotate', label: 'Look around' },
					{
						path: 'client.extra.antiafk.actions.rotateDelayTicks',
						label: 'Look around every',
						unit: TICKS,
						min: 1,
						advanced: true,
						showIf: 'client.extra.antiafk.actions.rotate'
					},
					{ path: 'client.extra.antiafk.actions.jump', label: 'Jump' },
					{ path: 'client.extra.antiafk.actions.jumpOnlyInLiquid', label: 'Only jump in water or lava', showIf: 'client.extra.antiafk.actions.jump' },
					{
						path: 'client.extra.antiafk.actions.jumpDelayTicks',
						label: 'Jump every',
						unit: TICKS,
						min: 1,
						advanced: true,
						showIf: 'client.extra.antiafk.actions.jump'
					},
					{ path: 'client.extra.antiafk.actions.sneak', label: 'Sneak' },
					{
						path: 'client.extra.antiafk.actions.sneakDelayTicks',
						label: 'Sneak every',
						unit: TICKS,
						min: 1,
						advanced: true,
						showIf: 'client.extra.antiafk.actions.sneak'
					},
					priority('client.extra.antiafk.priority', 5000)
				]
			},
			{
				id: 'spook',
				title: 'Look at players',
				description: 'Turn to stare at nearby players, so the account looks like someone is playing.',
				toggle: 'client.extra.spook.enabled',
				fields: [
					{
						path: 'client.extra.spook.spookTargetingMode',
						label: 'Look at',
						options: { VISUAL_RANGE: 'The player who came into view last', NEAREST: 'The nearest player' }
					},
					priority('client.extra.spook.priority', 1000)
				]
			},
			{
				id: 'antiKick',
				title: 'Inactive player kick',
				description: 'Kick people controlling the account when they go idle, so Anti-AFK takes over.',
				toggle: 'client.extra.antiKick.enabled',
				fields: [
					{ path: 'client.extra.antiKick.playerInactivityKickMins', label: 'Kick after', unit: 'minutes idle', min: 1 },
					{ path: 'client.extra.antiKick.minWalkDistance', label: 'Counts as active after walking', unit: 'blocks', min: 0 }
				]
			}
		]
	},
	{
		id: 'combat',
		title: 'Combat',
		icon: Swords,
		sections: [
			{
				id: 'killAura',
				title: 'Kill aura',
				description: 'Attack nearby mobs or players while nobody is controlling the account.',
				toggle: 'client.extra.killAura.enabled',
				fields: [
					{ path: 'client.extra.killAura.targetHostileMobs', label: 'Hostile mobs' },
					{ path: 'client.extra.killAura.targetNeutralMobs', label: 'Neutral mobs' },
					{
						path: 'client.extra.killAura.onlyNeutralAggressive',
						label: 'Only neutral mobs that are attacking',
						showIf: 'client.extra.killAura.targetNeutralMobs'
					},
					{ path: 'client.extra.killAura.targetPlayers', label: 'Players', help: 'Friends are never attacked.' },
					{ path: 'client.extra.killAura.targetCustom', label: 'Specific entity types' },
					{ path: 'client.extra.killAura.customTargets', label: 'Entity types', showIf: 'client.extra.killAura.targetCustom' },
					{ path: 'client.extra.killAura.ignoreNamedMobs', label: 'Spare mobs with name tags' },
					{ path: 'client.extra.killAura.priority', label: 'Target priority', options: { NONE: 'First found', NEAREST: 'Nearest' } },
					{ path: 'client.extra.killAura.switchWeapon', label: 'Switch to a weapon' },
					{
						path: 'client.extra.killAura.weaponType',
						label: 'Weapon',
						options: { ANY: 'Any', SWORD: 'Sword', AXE: 'Axe' },
						showIf: 'client.extra.killAura.switchWeapon'
					},
					{
						path: 'client.extra.killAura.weaponMaterial',
						label: 'Weapon material',
						options: { ANY: 'Any', NETHERITE: 'Netherite', DIAMOND: 'Diamond' },
						showIf: 'client.extra.killAura.switchWeapon'
					},
					{ path: 'client.extra.killAura.attackDelayTicks', label: 'Attack delay', unit: TICKS, min: 0 },
					{
						path: 'client.extra.killAura.tpsSync',
						label: 'Match the server’s tick rate',
						help: 'Stretches the attack delay when the server lags, so attacks stay fully charged.',
						advanced: true
					},
					{
						path: 'client.extra.killAura.raycast',
						label: 'Only attack targets in line of sight',
						help: 'Don’t hit through walls.',
						advanced: true
					},
					priority('client.extra.killAura.actionPriority', 8000)
				]
			},
			{
				id: 'spawnPatrol',
				title: 'Spawn patrol',
				description: 'Walk around spawn and chase down players it finds. Kill aura does the attacking.',
				toggle: 'client.extra.spawnPatrol.enabled',
				fields: [
					{ path: 'client.extra.spawnPatrol.goalX', label: 'Patrol around X' },
					{ path: 'client.extra.spawnPatrol.goalY', label: 'Patrol around Y' },
					{ path: 'client.extra.spawnPatrol.goalZ', label: 'Patrol around Z' },
					{ path: 'client.extra.spawnPatrol.maxPatrolRange', label: 'Stay within', unit: 'blocks', min: 1 },
					{ path: 'client.extra.spawnPatrol.targetOnlyNakeds', label: 'Only chase players without armor' },
					{ path: 'client.extra.spawnPatrol.targetAttackers', label: 'Chase players who attack it' },
					{ path: 'client.extra.spawnPatrol.ignoreFriends', label: 'Ignore friends' },
					{ path: 'client.extra.spawnPatrol.ignoreList', label: 'Never chase' },
					{
						path: 'client.extra.spawnPatrol.stickyTargeting',
						label: 'Keep chasing the same player',
						help: 'Don’t switch to someone closer once a chase has started.',
						advanced: true
					},
					{ path: 'client.extra.spawnPatrol.targetOnlyBedrock', label: 'Only chase Bedrock players', advanced: true },
					{
						path: 'client.extra.spawnPatrol.nether',
						label: 'Patrol the Nether',
						help: 'Walk to the nearest Nether portal when in another dimension.',
						advanced: true
					},
					{
						path: 'client.extra.spawnPatrol.stuckKill',
						label: 'Respawn when stuck',
						help: 'Uses /kill when it hasn’t moved far for a while.',
						advanced: true
					},
					{
						path: 'client.extra.spawnPatrol.stuckKillSeconds',
						label: 'Stuck after',
						unit: 'seconds',
						min: 1,
						advanced: true,
						showIf: 'client.extra.spawnPatrol.stuckKill'
					},
					{
						path: 'client.extra.spawnPatrol.stuckKillMinDist',
						label: 'Stuck when it moved less than',
						unit: 'blocks',
						min: 0,
						advanced: true,
						showIf: 'client.extra.spawnPatrol.stuckKill'
					},
					{
						path: 'client.extra.spawnPatrol.stuckKillAntiStuck',
						label: 'Walk and jump if /kill fails',
						advanced: true,
						showIf: 'client.extra.spawnPatrol.stuckKill'
					},
					priority('client.extra.spawnPatrol.priority', 6000)
				]
			},
			{
				id: 'friends',
				title: 'Friends',
				description: 'Players kill aura, visual range alerts and auto disconnect leave alone.',
				fields: [{ path: 'client.extra.friendsList', label: 'Friends' }]
			}
		]
	},
	{
		id: 'chat',
		title: 'Chat',
		icon: MessageSquare,
		sections: [
			{
				id: 'chatFilter',
				title: 'Chat filter',
				description: 'What shows up in chat for whoever is controlling the account.',
				fields: [
					{ path: 'client.extra.chat.hideChat', label: 'Hide public chat' },
					{ path: 'client.extra.chat.hideWhispers', label: 'Hide whispers' },
					{ path: 'client.extra.chat.hideDeathMessages', label: 'Hide death messages' },
					{ path: 'client.extra.chat.showConnectionMessages', label: 'Show join and leave messages' },
					{ path: 'client.extra.chat.insertClickableLinks', label: 'Make links clickable' },
					{ path: 'client.extra.chat.ignoreList', label: 'Ignored players' },
					{
						path: 'client.extra.chat.hide2b2tActionBarText',
						label: 'Hide 2b2t’s action bar text',
						help: 'The 2b2t.org messages above the hotbar.',
						advanced: true
					},
					{
						path: 'client.extra.chat.replace2b2tChatCommands',
						label: 'Handle /ignore commands in the proxy',
						help: 'Commands like /ignore use the proxy’s ignore list instead of the server’s.',
						advanced: true
					},
					{
						path: 'client.extra.chat.enabled',
						label: 'Chat features',
						help: 'Turning this off disables everything in this section and the message prefix and suffix.',
						advanced: true
					}
				]
			},
			{
				id: 'autoReply',
				title: 'Auto reply',
				description: 'Answer whispers while nobody is playing.',
				toggle: 'client.extra.autoReply.enabled',
				fields: [
					{ path: 'client.extra.autoReply.message', label: 'Reply', multiline: true },
					{ path: 'client.extra.autoReply.cooldownSeconds', label: 'Cooldown', unit: 'seconds', min: 0 }
				]
			},
			{
				id: 'spammer',
				title: 'Spammer',
				description: 'Send messages on a timer.',
				toggle: 'client.extra.spammer.enabled',
				fields: [
					{ path: 'client.extra.spammer.messages', label: 'Messages' },
					{ path: 'client.extra.spammer.delayTicks', label: 'Interval', unit: TICKS, min: 1 },
					{ path: 'client.extra.spammer.randomOrder', label: 'Random order' },
					{ path: 'client.extra.spammer.appendRandom', label: 'Add random characters', help: 'Gets past duplicate message filters.' },
					{ path: 'client.extra.spammer.whisper', label: 'Whisper to online players instead' },
					{ path: 'client.extra.spammer.whilePlayerConnected', label: 'Also while you’re playing' }
				]
			},
			{
				id: 'chatDecoration',
				title: 'Message prefix and suffix',
				description: 'Add text to every message you send.',
				fields: [
					{ path: 'client.extra.chat.prefixChats', label: 'Add a prefix' },
					{ path: 'client.extra.chat.prefix', label: 'Prefix', showIf: 'client.extra.chat.prefixChats' },
					{ path: 'client.extra.chat.suffixChats', label: 'Add a suffix' },
					{
						path: 'client.extra.chat.randomSuffix',
						label: 'Use random characters as the suffix',
						help: 'Gets past duplicate message filters.',
						showIf: 'client.extra.chat.suffixChats'
					},
					{
						path: 'client.extra.chat.suffix',
						label: 'Suffix',
						showIf: (v) => v['client.extra.chat.suffixChats'] === true && v['client.extra.chat.randomSuffix'] !== true
					}
				]
			},
			{
				id: 'whisperCommand',
				title: 'Whisper command',
				description: 'How the account whispers on this server, for auto reply, the spammer and visual range whispers.',
				advanced: true,
				fields: [{ path: 'client.extra.whisperCommand', label: 'Command', placeholder: 'msg', help: 'Without the slash, e.g. msg, w or tell.' }]
			}
		]
	},
	{
		id: 'access',
		title: 'Joining',
		icon: Users,
		sections: [
			{
				id: 'spectators',
				title: 'Spectators',
				description: 'Let people watch without controlling the account. Who may join is set on the instance’s Sharing tab.',
				toggle: 'server.spectator.allowSpectator',
				fields: [
					{ path: 'server.spectator.spectatorEntity', label: 'Appear as', placeholder: 'cat', help: 'An entity type, e.g. cat, parrot or bat.' },
					{
						path: 'server.spectator.chatSendsPrivateMessage',
						label: 'Keep spectator chat on the proxy',
						help: 'Their messages only reach the player and other spectators.'
					},
					{
						path: 'server.spectator.spectatorPublicChatEnabled',
						label: 'Spectators can chat publicly',
						showIf: (v) => v['server.spectator.chatSendsPrivateMessage'] !== true
					},
					{ path: 'server.spectator.playerCamOnJoin', label: 'Start in first person view' },
					{
						path: 'server.spectator.fullCommandsEnabled',
						label: 'Spectators can use all commands',
						help: 'Otherwise only a few basic ones.',
						advanced: true
					},
					{
						path: 'server.spectator.fullCommandsRequireRegularWhitelist',
						label: 'Only if they may also play',
						help: 'Spectator-only guests keep the basic commands.',
						advanced: true,
						showIf: 'server.spectator.fullCommandsEnabled'
					},
					{
						path: 'server.spectator.fullCommandsAcceptSlashCommands',
						label: 'Accept commands typed with a slash',
						advanced: true,
						showIf: 'server.spectator.fullCommandsEnabled'
					},
					{
						path: 'server.spectator.fullCommandsServerCommands',
						label: 'Show commands in the command list',
						help: 'Adds the proxy’s commands to tab completion for spectators.',
						advanced: true,
						showIf: 'server.spectator.fullCommandsEnabled'
					}
				]
			},
			{
				id: 'joinOptions',
				title: 'When you join',
				description: 'In-game behavior while you’re playing through the proxy.',
				fields: [
					{ path: 'server.welcomeMessages', label: 'Show welcome messages' },
					{ path: 'server.preferLoginAsController', label: 'Take control when joining', help: 'Otherwise you join as a spectator if someone is playing.' },
					{ path: 'client.extra.autoConnectOnLogin', label: 'Connect when you join', help: 'If the account is offline, connect it to the server.' },
					{
						path: 'server.injectTablistFooter',
						label: 'Show proxy info in the tab list',
						help: 'Your name, ping and the account’s name under the player list.',
						advanced: true
					}
				]
			},
			{
				id: 'chatHistory',
				title: 'Recent chat on join',
				description: 'Show the latest chat, whispers and server messages when someone joins.',
				toggle: 'server.extra.chatHistory.enable',
				fields: [
					{ path: 'server.extra.chatHistory.maxCount', label: 'Show up to', unit: 'messages', min: 1 },
					{ path: 'server.extra.chatHistory.seconds', label: 'From the last', unit: 'seconds', min: 1 },
					{ path: 'server.extra.chatHistory.spectators', label: 'Also for spectators' }
				]
			},
			{
				id: 'inGameCommands',
				title: 'In-game commands',
				description: 'Control the proxy by typing commands in Minecraft chat.',
				toggle: 'inGameCommands.enable',
				fields: [
					{ path: 'inGameCommands.prefix', label: 'Command prefix', placeholder: '!' },
					{ path: 'inGameCommands.slashCommands', label: 'Also accept commands with a slash' },
					{
						path: 'inGameCommands.slashCommandsReplacesServerCommands',
						label: 'Never pass slash commands to the server',
						help: 'Otherwise commands the proxy doesn’t know go to the server.',
						advanced: true,
						showIf: 'inGameCommands.slashCommands'
					},
					{ path: 'inGameCommands.suggestions', label: 'Suggest commands while typing', advanced: true },
					{
						path: 'inGameCommands.allowWhitelistedToUseAccountOwnerCommands',
						label: 'Everyone who may play can use owner commands',
						help: 'Otherwise only someone logged in with the account itself can.',
						advanced: true
					}
				]
			}
		]
	},
	{
		id: 'places',
		title: 'Places',
		icon: MapPin,
		sections: [
			{
				id: 'waypoints',
				title: 'Waypoints',
				description: 'Named locations, for pathfinding and quick reference.',
				fields: [
					{
						path: 'client.extra.waypoints.waypoints',
						label: 'Waypoints',
						addLabel: 'Add waypoint',
						columns: {
							id: { label: 'Name', unique: true, placeholder: 'base' },
							dimension: {
								label: 'Dimension',
								width: '8rem',
								default: 'overworld',
								options: { overworld: 'Overworld', the_nether: 'Nether', the_end: 'End' }
							},
							x: { label: 'X', ...COORD },
							y: { label: 'Y', ...COORD, default: 64 },
							z: { label: 'Z', ...COORD }
						}
					}
				]
			},
			{
				id: 'pearlLoader',
				title: 'Pearl loader',
				description: 'Stasis chambers the account can trigger to load someone’s ender pearl.',
				fields: [
					{
						path: 'client.extra.pearlLoader.pearls',
						label: 'Pearls',
						addLabel: 'Add pearl',
						columns: {
							id: { label: 'Owner', unique: true, placeholder: 'Player name' },
							x: { label: 'X', ...COORD },
							y: { label: 'Y', ...COORD, default: 64 },
							z: { label: 'Z', ...COORD }
						}
					},
					{ path: 'client.extra.pearlLoader.returnToStartPos', label: 'Walk back afterwards' },
					{
						path: 'client.extra.pearlLoader.doubleClick',
						label: 'Click twice',
						help: 'For chambers that need the trapdoor or lever flipped back.',
						advanced: true
					}
				]
			},
			{
				id: 'pathfinder',
				title: 'Pathfinding',
				description: 'How the account walks to waypoints, pearls, players and blocks.',
				fields: [
					{ path: 'client.extra.pathfinder.allowBreak', label: 'Break blocks in the way' },
					{ path: 'client.extra.pathfinder.allowPlace', label: 'Place blocks to bridge and pillar up' },
					{ path: 'client.extra.pathfinder.allowSprint', label: 'Sprint' },
					{ path: 'client.extra.pathfinder.allowParkour', label: 'Parkour jumps' },
					{ path: 'client.extra.pathfinder.followRadius', label: 'Follow players at', unit: 'blocks', min: 0 },
					{ path: 'client.extra.pathfinder.autoTool', label: 'Switch to the best tool' },
					{ path: 'client.extra.pathfinder.renderPath', label: 'Show the path in-game', help: 'Draws it with particles for whoever is playing.' },
					{
						path: 'client.extra.pathfinder.acceptableThrowawayItems',
						label: 'Blocks it may place',
						placeholder: 'cobblestone',
						help: 'Item IDs.',
						advanced: true,
						showIf: 'client.extra.pathfinder.allowPlace'
					},
					{ path: 'client.extra.pathfinder.blocksToAvoid', label: 'Blocks to walk around', placeholder: 'magma_block', help: 'Block IDs.', advanced: true },
					{
						path: 'client.extra.pathfinder.blocksToDisallowBreaking',
						label: 'Blocks it must never break',
						placeholder: 'chest',
						help: 'Block IDs.',
						advanced: true
					},
					{
						path: 'client.extra.pathfinder.allowBreakAnyway',
						label: 'Blocks it may always break',
						placeholder: 'cobweb',
						help: 'Block IDs. Broken even while breaking blocks is off.',
						advanced: true
					},
					{
						path: 'client.extra.pathfinder.allowInventory',
						label: 'Move items into the hotbar',
						help: 'To get blocks to place and tools.',
						advanced: true
					},
					{ path: 'client.extra.pathfinder.allowDownward', label: 'Dig straight down', advanced: true },
					{
						path: 'client.extra.pathfinder.allowParkourPlace',
						label: 'Place blocks during parkour jumps',
						advanced: true,
						showIf: 'client.extra.pathfinder.allowParkour'
					},
					{ path: 'client.extra.pathfinder.allowParkourAscend', label: 'Parkour jumps upwards', advanced: true, showIf: 'client.extra.pathfinder.allowParkour' },
					{ path: 'client.extra.pathfinder.allowDiagonalAscend', label: 'Climb diagonally', advanced: true },
					{ path: 'client.extra.pathfinder.allowDiagonalDescend', label: 'Drop down diagonally', advanced: true },
					{ path: 'client.extra.pathfinder.diagonalCentering', label: 'Center on blocks when moving diagonally', advanced: true },
					{ path: 'client.extra.pathfinder.traverseCentering', label: 'Center on blocks when walking straight', advanced: true },
					{ path: 'client.extra.pathfinder.maxFallHeightNoWater', label: 'Longest fall without water', unit: 'blocks', min: 0, advanced: true },
					{ path: 'client.extra.pathfinder.allowLongFall', label: 'Take longer falls that hurt', advanced: true },
					{
						path: 'client.extra.pathfinder.longFallCostLogMultiplier',
						label: 'Long fall cost multiplier',
						min: 0,
						advanced: true,
						showIf: 'client.extra.pathfinder.allowLongFall'
					},
					{
						path: 'client.extra.pathfinder.longFallCostAddCost',
						label: 'Long fall extra cost',
						min: 0,
						advanced: true,
						showIf: 'client.extra.pathfinder.allowLongFall'
					},
					{
						path: 'client.extra.pathfinder.blockPlacementPenalty',
						label: 'Cost of placing a block',
						min: 0,
						help: 'Higher values make it place fewer blocks.',
						advanced: true
					},
					{
						path: 'client.extra.pathfinder.blockBreakAdditionalCost',
						label: 'Extra cost of breaking a block',
						min: 0,
						help: 'Higher values make it break fewer blocks.',
						advanced: true
					},
					{ path: 'client.extra.pathfinder.jumpPenalty', label: 'Cost of jumping', min: 0, advanced: true },
					{ path: 'client.extra.pathfinder.lavaWalkCost', label: 'Cost of walking through lava', min: 0, advanced: true },
					{
						path: 'client.extra.pathfinder.assumeExternalAutoTool',
						label: 'Plan as if holding the best tool',
						help: 'For when something else switches tools.',
						advanced: true,
						showIf: 'client.extra.pathfinder.autoTool'
					},
					{ path: 'client.extra.pathfinder.preferSilkTouch', label: 'Prefer Silk Touch tools', advanced: true },
					{ path: 'client.extra.pathfinder.itemSaver', label: 'Don’t use tools that are about to break', advanced: true },
					{
						path: 'client.extra.pathfinder.itemSaverThreshold',
						label: 'Keep tools with at least',
						unit: 'durability',
						min: 0,
						advanced: true,
						showIf: 'client.extra.pathfinder.itemSaver'
					},
					{ path: 'client.extra.pathfinder.avoidUpdatingFallingBlocks', label: 'Don’t break blocks under sand or gravel', advanced: true },
					{ path: 'client.extra.pathfinder.pauseMiningForFallingBlocks', label: 'Wait for falling sand and gravel', advanced: true },
					{ path: 'client.extra.pathfinder.placeBlockVerifyAbleToPlace', label: 'Check a block fits before placing it', advanced: true },
					{ path: 'client.extra.pathfinder.placeBlockSneak', label: 'Sneak while placing blocks', advanced: true },
					{ path: 'client.extra.pathfinder.mineProcessExitLavaGoal', label: 'Get out of lava while mining', advanced: true },
					{
						path: 'client.extra.pathfinder.getToBlockExploreForBlocks',
						label: 'Explore when the block isn’t nearby',
						help: 'When asked to go to a block type it hasn’t seen yet.',
						advanced: true
					},
					{
						path: 'client.extra.pathfinder.getToBlockBlacklistClosestOnFailure',
						label: 'Try the next block when one can’t be reached',
						advanced: true
					},
					{
						path: 'client.extra.pathfinder.interactWithProcessMaxPathTries',
						label: 'Tries to reach a block to click it',
						unit: 'attempts',
						min: 1,
						advanced: true
					},
					{
						path: 'client.extra.pathfinder.simplifyUnloadedYGoal',
						label: 'Ignore the height of far-away goals',
						help: 'Aims for the X and Z only while the goal’s chunk isn’t loaded.',
						advanced: true
					},
					{
						path: 'client.extra.pathfinder.renderPathDetailed',
						label: 'Show every block of the path',
						advanced: true,
						showIf: 'client.extra.pathfinder.renderPath'
					},
					{
						path: 'client.extra.pathfinder.pathRenderIntervalTicks',
						label: 'Redraw the path every',
						unit: TICKS,
						min: 1,
						advanced: true,
						showIf: 'client.extra.pathfinder.renderPath'
					},
					{ path: 'client.extra.pathfinder.teleportDelayMs', label: 'Pause after being teleported', unit: 'ms', min: 0, advanced: true },
					{ path: 'client.extra.pathfinder.primaryTimeoutMs', label: 'Time to search for a path', unit: 'ms', min: 1, advanced: true },
					{
						path: 'client.extra.pathfinder.failureTimeoutMs',
						label: 'Keep searching if nothing is found, up to',
						unit: 'ms',
						min: 1,
						advanced: true
					},
					{
						path: 'client.extra.pathfinder.planAheadPrimaryTimeoutMs',
						label: 'Time to plan the next stretch',
						unit: 'ms',
						min: 1,
						advanced: true
					},
					{
						path: 'client.extra.pathfinder.planAheadFailureTimeoutMs',
						label: 'Keep planning the next stretch, up to',
						unit: 'ms',
						min: 1,
						advanced: true
					},
					{ path: 'client.extra.pathfinder.failedPathSearchCooldownMs', label: 'Wait after a failed search', unit: 'ms', min: 0, advanced: true },
					priority('client.extra.pathfinder.priority', 7000)
				]
			},
			{
				id: 'serverSwitcher',
				title: 'Server switcher',
				description: 'Other servers players can be sent to from inside the proxy.',
				fields: [
					{
						path: 'server.extra.serverSwitcher.servers',
						label: 'Servers',
						addLabel: 'Add server',
						columns: {
							name: { label: 'Name', unique: true, placeholder: 'backup' },
							address: { label: 'Address', placeholder: 'play.example.com' },
							port: { label: 'Port', width: '6rem', default: 25565, min: 1, max: 65535 }
						}
					}
				]
			}
		]
	},
	{
		id: 'discord',
		title: 'Discord',
		icon: Bell,
		sections: [
			{
				id: 'notifyConnection',
				title: 'Connection notifications',
				description: 'What the instance’s Discord channel reports about its connection.',
				fields: [
					{ path: 'discord.notifications.connecting', label: 'Connecting' },
					{ path: 'discord.notifications.connected', label: 'Connected' },
					{ path: 'discord.notifications.online', label: 'Online' },
					{ path: 'discord.notifications.disconnected', label: 'Disconnected' },
					{ path: 'discord.notifications.autoReconnect', label: 'Reconnecting' },
					{ path: 'discord.notifications.loginFailed', label: 'Login failed' },
					{ path: 'discord.notifications.serverRestart', label: 'Server restarting' },
					{ path: 'client.extra.reconfiguringNotification', label: 'Reconfiguring' },
					{ path: 'discord.notifications.sessionTimeLimit', label: 'Session time limit warnings' },
					{ path: 'discord.notifications.activeHours', label: 'Active hours connects' }
				]
			},
			{
				id: 'notifyQueue',
				title: 'Queue notifications',
				description: 'Messages about the server’s queue.',
				fields: [
					{ path: 'discord.notifications.queueStarted', label: 'Started queueing' },
					{ path: 'discord.notifications.queueWarning', label: 'Queue position alerts' },
					{ path: 'client.extra.prioStatusChangeMention', label: 'Priority queue status changed' }
				]
			},
			{
				id: 'notifyDanger',
				title: 'Danger notifications',
				description: 'Messages when the account is hurt or in trouble.',
				fields: [
					{ path: 'discord.notifications.death', label: 'Died' },
					{ path: 'discord.notifications.deathMessage', label: 'Death message' },
					{ path: 'discord.notifications.healthDisconnect', label: 'Disconnected for low health' },
					{ path: 'client.extra.autoTotem.totemPopAlert', label: 'Totem popped' },
					{ path: 'client.extra.autoTotem.noTotemsAlert', label: 'Out of totems' },
					{ path: 'client.extra.autoEat.warning', label: 'Out of food' },
					{ path: 'client.extra.killMessage', label: 'Killed a player' }
				]
			},
			{
				id: 'notifyPlayers',
				title: 'Player notifications',
				description: 'Messages about other players.',
				fields: [
					{ path: 'client.extra.visualRange.enterAlert', label: 'Player entered visual range' },
					{ path: 'client.extra.visualRange.leaveAlert', label: 'Player left visual range' },
					{ path: 'client.extra.visualRange.logoutAlert', label: 'Player logged out in visual range' },
					{ path: 'discord.clientConnectionMessages', label: 'Players joining and leaving the instance' },
					{ path: 'discord.notifications.blockedLogins', label: 'Blocked logins', help: 'Someone without access tried to join.' },
					{ path: 'discord.mcVersionMismatchWarning', label: 'Minecraft version mismatch warnings' },
					{ path: 'discord.reportCoords', label: 'Coordinates in messages' }
				]
			},
			{
				id: 'notifyOther',
				title: 'Other notifications',
				description: 'Everything else the bot reports.',
				fields: [
					{ path: 'client.extra.tasks.taskCommandExecutedNotification', label: 'Scheduled tasks ran' },
					{ path: 'discord.notifications.spawnPatrol', label: 'Spawn patrol targets' },
					{ path: 'discord.notifications.replays', label: 'Replay recordings' },
					{ path: 'discord.notifications.plugins', label: 'Plugins' }
				]
			},
			{
				id: 'notifications',
				title: 'Pings',
				description: 'When the Discord bot pings the account owner in the instance’s channel.',
				fields: [
					{ path: 'discord.mentionRoleOnConnect', label: 'Connected' },
					{ path: 'discord.mentionRoleOnDisconnect', label: 'Disconnected' },
					{ path: 'discord.mentionRoleOnStartQueue', label: 'Started queueing' },
					{ path: 'discord.mentionRoleOnDeath', label: 'Died' },
					{ path: 'client.extra.utility.actions.autoDisconnect.mentionOnDisconnect', label: 'Auto disconnect logged out' },
					{ path: 'client.extra.autoTotem.totemPopAlertMention', label: 'Totem popped' },
					{ path: 'client.extra.autoTotem.noTotemsAlertMention', label: 'Out of totems' },
					{ path: 'client.extra.autoEat.warningMention', label: 'Out of food' },
					{ path: 'client.extra.visualRange.enterAlertMention', label: 'Player entered visual range' },
					{ path: 'discord.mentionRoleOnPlayerOnline', label: 'A watched player came online' },
					{ path: 'discord.mentionRoleOnServerRestart', label: 'Server restarting' },
					{ path: 'discord.mentionRoleOnLoginFailed', label: 'Login failed' },
					{ path: 'discord.mentionRoleOnPrioUpdate', label: 'Priority queue status changed' },
					{ path: 'discord.mentionRoleOnDeviceCodeAuth', label: 'Microsoft login needed' },
					{ path: 'discord.mentionOnClientConnected', label: 'Someone started playing' },
					{ path: 'discord.mentionOnClientDisconnected', label: 'Someone stopped playing' },
					{ path: 'discord.mentionOnSpectatorConnected', label: 'A spectator joined' },
					{ path: 'discord.mentionOnSpectatorDisconnected', label: 'A spectator left' },
					{ path: 'discord.mentionOnNonWhitelistedClientConnected', label: 'Someone without access tried to join' }
				]
			},
			{
				id: 'theme',
				title: 'Message colors',
				description: 'The accent colors of the bot’s Discord messages.',
				advanced: true,
				fields: [
					{ path: 'theme.primary', label: 'Normal messages' },
					{ path: 'theme.success', label: 'Success, joins and friends' },
					{ path: 'theme.error', label: 'Errors, leaves and strangers' },
					{ path: 'theme.inQueue', label: 'In queue and reconnecting' }
				]
			}
		]
	},
	{
		id: 'extras',
		title: 'Extras',
		icon: Sparkles,
		sections: [
			{
				id: 'queueWarning',
				title: 'Queue position alerts',
				description: 'Get alerted when the queue reaches certain positions.',
				toggle: 'client.extra.queueWarning.enabled',
				fields: [
					{ path: 'client.extra.queueWarning.warningPositions', label: 'Alert at positions' },
					{ path: 'client.extra.queueWarning.mentionPositions', label: 'Ping on Discord at positions' }
				]
			},
			{
				id: 'replayMod',
				title: 'Replay recording',
				description: 'Record ReplayMod replays of what the account sees.',
				fields: [
					{
						path: 'client.extra.replayMod.autoRecordMode',
						label: 'Record automatically',
						options: {
							NONE: 'Never',
							PROXY_CONNECTED: 'While connected to the server',
							ONLINE: 'While online, after the queue',
							PLAYER_CONNECTED: 'While someone is playing',
							HEALTH: 'While health is low'
						}
					},
					{
						path: 'client.extra.replayMod.replayRecordingHealthThreshold',
						label: 'Record below health',
						unit: 'of 20',
						min: 1,
						max: 20,
						showIf: isMode('client.extra.replayMod.autoRecordMode', 'HEALTH')
					},
					{ path: 'client.extra.visualRange.replayRecording', label: 'Record while players are in view' },
					{
						path: 'client.extra.visualRange.replayRecordingMode',
						label: 'Players to record',
						options: { ENEMY: 'Anyone who isn’t a friend', ALL: 'Anyone' },
						showIf: 'client.extra.visualRange.replayRecording'
					},
					{
						path: 'client.extra.visualRange.replayRecordingCooldownMins',
						label: 'Keep recording after they leave for',
						unit: 'minutes',
						min: 0,
						showIf: 'client.extra.visualRange.replayRecording'
					},
					{
						path: 'client.extra.replayMod.maxRecordingTimeMins',
						label: 'Stop recordings after',
						unit: 'minutes',
						min: 0,
						help: '0 records until the account disconnects.'
					},
					{ path: 'client.extra.replayMod.sendRecordingsToDiscord', label: 'Upload recordings to Discord' },
					{
						path: 'client.extra.replayMod.fileIOUploadIfTooLarge',
						label: 'Upload large recordings to file.io',
						help: 'For recordings over Discord’s size limit.',
						showIf: 'client.extra.replayMod.sendRecordingsToDiscord'
					},
					{
						path: 'client.extra.replayMod.featureFlags',
						label: 'Keep the server’s feature flags',
						help: 'Turn off if ReplayMod can’t open recordings from servers with experimental features.',
						advanced: true
					}
				]
			},
			{
				id: 'autoFish',
				title: 'Auto fish',
				description: 'Fish automatically. Stand facing water with a rod in hand.',
				toggle: 'client.extra.autoFish.enabled',
				fields: [
					{ path: 'client.extra.autoFish.yaw', label: 'Look direction (yaw)' },
					{ path: 'client.extra.autoFish.pitch', label: 'Look angle (pitch)', min: -90, max: 90 },
					priority('client.extra.autoFish.priority', 2000)
				]
			},
			{
				id: 'autoDrop',
				title: 'Auto drop',
				description: 'Throw items out of the inventory.',
				toggle: 'client.extra.autoDrop.enabled',
				fields: [
					{
						path: 'client.extra.autoDrop.mode',
						label: 'Items to drop',
						options: { ALL: 'Everything', WHITELIST: 'Only the items listed', BLACKLIST: 'Everything except the items listed' }
					},
					{
						path: 'client.extra.autoDrop.items',
						label: 'Item list',
						placeholder: 'rotten_flesh',
						help: 'Item IDs, e.g. rotten_flesh.',
						showIf: (v) => v['client.extra.autoDrop.mode'] !== 'ALL'
					},
					{ path: 'client.extra.autoDrop.dropStack', label: 'Drop whole stacks', help: 'Off drops one item at a time.' },
					{ path: 'client.extra.autoDrop.delayTicks', label: 'Drop every', unit: TICKS, min: 0, advanced: true },
					{ path: 'client.extra.autoDrop.requiresRotation', label: 'Face a direction while dropping', advanced: true },
					{
						path: 'client.extra.autoDrop.yaw',
						label: 'Look direction (yaw)',
						advanced: true,
						showIf: 'client.extra.autoDrop.requiresRotation'
					},
					{
						path: 'client.extra.autoDrop.pitch',
						label: 'Look angle (pitch)',
						min: -90,
						max: 90,
						advanced: true,
						showIf: 'client.extra.autoDrop.requiresRotation'
					},
					priority('client.extra.autoDrop.priority', 3000)
				]
			},
			{
				id: 'click',
				title: 'Hold click',
				description: 'Keep clicking what the account is looking at, e.g. for mob farms.',
				fields: [
					{ path: 'client.extra.click.holdLeftClick', label: 'Hold left click' },
					{
						path: 'client.extra.click.holdLeftClickInterval',
						label: 'Left click every',
						unit: TICKS,
						min: 0,
						help: '0 holds the button down, e.g. to mine.',
						showIf: 'client.extra.click.holdLeftClick'
					},
					{ path: 'client.extra.click.holdRightClick', label: 'Hold right click' },
					{
						path: 'client.extra.click.holdRightClickInterval',
						label: 'Right click every',
						unit: TICKS,
						min: 0,
						showIf: 'client.extra.click.holdRightClick'
					},
					{
						path: 'client.extra.click.holdRightClickMode',
						label: 'Right click with',
						options: { MAIN_HAND: 'Main hand', OFF_HAND: 'Offhand', ALTERNATE_HANDS: 'Both hands in turn' },
						showIf: 'client.extra.click.holdRightClick'
					},
					{
						path: 'client.extra.click.holdClickTarget',
						label: 'Only click on',
						options: { ANY: 'Anything', BLOCK: 'Blocks', ENTITY: 'Entities', NONE: 'Air' },
						advanced: true
					},
					{ path: 'client.extra.click.holdSneak', label: 'Sneak while clicking', advanced: true },
					{ path: 'client.extra.click.hasRotation', label: 'Face a fixed direction', advanced: true },
					{ path: 'client.extra.click.rotationYaw', label: 'Look direction (yaw)', advanced: true, showIf: 'client.extra.click.hasRotation' },
					{
						path: 'client.extra.click.rotationPitch',
						label: 'Look angle (pitch)',
						min: -90,
						max: 90,
						advanced: true,
						showIf: 'client.extra.click.hasRotation'
					},
					{ path: 'client.extra.click.additionalBlockReach', label: 'Extra block reach', unit: 'blocks', advanced: true },
					{ path: 'client.extra.click.additionalEntityReach', label: 'Extra entity reach', unit: 'blocks', advanced: true },
					priority('client.extra.click.priority', 9000)
				]
			},
			{
				id: 'tasks',
				title: 'Scheduled tasks',
				description: 'Run the instance’s scheduled tasks.',
				toggle: 'client.extra.tasks.enabled',
				advanced: true,
				fields: [{ path: 'client.extra.tasks.logCommandActionOutput', label: 'Log what tasks do' }]
			},
			{
				id: 'inventory',
				title: 'Inventory handling',
				description: 'How the account moves items and deals with open containers.',
				advanced: true,
				fields: [
					{ path: 'client.inventory.actionDelayTicks', label: 'Wait between item moves', unit: TICKS, min: 0 },
					{ path: 'client.inventory.autoCloseOpenContainers', label: 'Close containers left open' },
					{
						path: 'client.inventory.autoCloseOpenContainerAfterSeconds',
						label: 'Close them after',
						unit: 'seconds',
						min: 1,
						showIf: 'client.inventory.autoCloseOpenContainers'
					},
					{
						path: 'client.inventory.ncpStrict',
						label: 'Strict anti-cheat mode',
						help: 'Moves items more carefully, for servers with NoCheatPlus.'
					}
				]
			},
			{
				id: 'botBehavior',
				title: 'Game speed and view',
				description: 'How the account runs while nobody is playing.',
				advanced: true,
				fields: [
					{
						path: 'client.tickRate',
						label: 'Game speed',
						unit: '× normal',
						min: 0.1,
						help: '1 is the normal 20 ticks a second, 0.5 is half speed, 2 is double.'
					},
					{
						path: 'client.defaultClientRenderDistance',
						label: 'Render distance while nobody is playing',
						unit: 'chunks',
						min: 2,
						max: 32
					}
				]
			}
		]
	},
	{
		id: 'privacy',
		title: 'Privacy',
		icon: EyeOff,
		sections: [
			{
				id: 'vanillaClient',
				title: 'Look like a normal client',
				description: 'What the server can tell about the account’s client. All on by default.',
				fields: [
					{
						path: 'client.privacy.vanillaClientInfo',
						label: 'Send a regular player’s game settings',
						help: 'Language, view distance, main hand and particles like a normal install, picked once per account. Off sends the old proxy settings: 25 chunks, minimal particles, hidden from the server list.'
					},
					{
						path: 'client.privacy.clientLocale',
						label: 'Game language',
						placeholder: 'en_us',
						showIf: 'client.privacy.vanillaClientInfo',
						help: 'The language the server sees, like en_us or de_de.'
					},
					{
						path: 'client.privacy.steadyClientInfo',
						label: 'Keep the account’s settings while you play',
						help: 'Otherwise your own client’s language and view distance replace the account’s whenever you connect, a switch the server can notice.'
					},
					{
						path: 'client.privacy.humanResponseDelay',
						label: 'Answer pings on the next game tick',
						help: 'A real client answers keep-alives and pings with its next tick, not instantly.'
					},
					{
						path: 'client.privacy.downloadResourcePacks',
						label: 'Download server resource packs',
						help: 'Downloads packs like the game does and reports them loaded after a realistic delay. Off reports them loaded straight away without downloading.'
					},
					{
						path: 'client.privacy.smoothHandoff',
						label: 'Keep moving when the bot takes over',
						help: 'When you leave mid-jump or mid-walk, the bot carries on with your momentum instead of stopping dead in the air.'
					},
					{
						path: 'client.ping.sendPingRequests',
						label: 'Measure ping with extra requests',
						advanced: true,
						help: 'The game only sends these while its debug ping chart is open. Off takes the ping from the server’s player list.'
					}
				]
			},
			{
				id: 'extraProtection',
				title: 'Extra protection',
				description: 'Off by default. Ideas borrowed from the OpSec mod, mostly protecting your own client while you play through the account.',
				fields: [
					{
						path: 'client.privacy.vanillaSpoof',
						label: 'Hide your client’s mods',
						help: 'Drops the mod channels your client announces while you play, so the server sees an unmodded game.'
					},
					{
						path: 'client.privacy.blockCookies',
						label: 'Block server cookies',
						help: 'Servers can store cookies and read them back after a transfer to recognise a client. Blocked cookies are never stored and always read back empty.'
					},
					{
						path: 'client.privacy.blockLocalPackUrls',
						label: 'Block resource packs from private networks',
						help: 'Refuses packs hosted on a local network address like 192.168.x.x, which servers use to scan your network. Not applied while the server itself is local.'
					},
					{
						path: 'client.privacy.scrubPackHeaders',
						label: 'Hide the account in pack downloads',
						help: 'Pack downloads send a placeholder name and UUID instead of the account’s.'
					},
					{
						path: 'client.privacy.translationProbeProtection',
						label: 'Answer sign probes like vanilla',
						help: 'Servers hide translation keys and keybinds in signs to spot mods and changed keys from what your client shows. The proxy sends back what an unmodded English client with default keys would.'
					},
					{
						path: 'client.privacy.trustedSkinHostsOnly',
						label: 'Only load skins from Mojang',
						help: 'Removes skins and capes hosted anywhere else, which could log your IP.'
					}
				]
			}
		]
	},
	{
		id: 'network',
		title: 'Network',
		icon: Network,
		sections: [
			{
				id: 'clientTimeout',
				title: 'Server timeout',
				description: 'Disconnect when the server stops responding.',
				toggle: 'client.timeout.enable',
				advanced: true,
				fields: [{ path: 'client.timeout.seconds', label: 'Disconnect after', unit: 'seconds of silence', min: 1 }]
			},
			{
				id: 'clientNetwork',
				title: 'Connection to the server',
				description: 'Low-level settings for the account’s connection.',
				advanced: true,
				fields: [
					{
						path: 'client.compressionLevel',
						label: 'Compression level',
						min: -1,
						max: 9,
						help: '-1 uses the default, 0 is fastest, 9 is smallest.'
					},
					{
						path: 'client.bindAddress',
						label: 'Local address',
						placeholder: '0.0.0.0',
						help: 'The network interface to connect from. 0.0.0.0 picks automatically.'
					},
					{
						path: 'client.ping.pingIntervalSeconds',
						label: 'Measure ping every',
						unit: 'seconds',
						min: 1,
						showIf: 'client.ping.sendPingRequests'
					},
					{ path: 'client.ping.pingQueueTimeoutMs', label: 'Ping timeout in the queue', unit: 'ms', min: 1 },
					{
						path: 'client.keepAliveHandling.keepAliveMode',
						label: 'Keep-alive replies',
						options: { PASSTHROUGH: 'From the player while someone is playing', INDEPENDENT: 'Always from the proxy' }
					},
					{ path: 'client.keepAliveHandling.keepAliveQueueTimeoutMs', label: 'Keep-alive timeout in the queue', unit: 'ms', min: 1 }
				]
			},
			{
				id: 'chatSigning',
				title: 'Chat signing',
				description: 'Signed chat messages, which servers can use to report players.',
				advanced: true,
				fields: [
					{ path: 'client.chatSigning.enabled', label: 'Sign messages when the server asks for it' },
					{
						path: 'client.chatSigning.force',
						label: 'Always sign messages',
						help: 'Even on servers that don’t require it.',
						showIf: 'client.chatSigning.enabled'
					},
					{ path: 'client.chatSigning.signCommands', label: 'Sign commands like /msg', showIf: 'client.chatSigning.enabled' },
					{
						path: 'server.chatSigning.mode',
						label: 'Show others’ messages to players as',
						options: {
							PASSTHROUGH: 'Signed messages, unchanged',
							DISGUISED: 'Unsigned player messages',
							SYSTEM: 'Server messages'
						},
						help: 'Unsigned avoids “chat validation” disconnects for players.'
					}
				]
			},
			{
				id: 'playerConnections',
				title: 'Player connections',
				description: 'Low-level settings for players connecting to the proxy.',
				advanced: true,
				fields: [
					{ path: 'server.extra.timeout.enable', label: 'Disconnect players who stop responding' },
					{
						path: 'server.extra.timeout.seconds',
						label: 'Disconnect after',
						unit: 'seconds of silence',
						min: 1,
						showIf: 'server.extra.timeout.enable'
					},
					{
						path: 'server.compressionThreshold',
						label: 'Compress packets larger than',
						unit: 'bytes',
						min: -1,
						help: '-1 turns compression off.'
					},
					{
						path: 'server.compressionLevel',
						label: 'Compression level',
						min: -1,
						max: 9,
						help: '-1 uses the default, 0 is fastest, 9 is smallest.'
					},
					{ path: 'server.loginTimeout.enabled', label: 'Time out slow logins' },
					{ path: 'server.loginTimeout.handshakeTimeoutTicks', label: 'Handshake timeout', unit: TICKS, min: 1, showIf: 'server.loginTimeout.enabled' },
					{ path: 'server.loginTimeout.statusTimeoutTicks', label: 'Server list timeout', unit: TICKS, min: 1, showIf: 'server.loginTimeout.enabled' },
					{ path: 'server.loginTimeout.loginTimeoutTicks', label: 'Login timeout', unit: TICKS, min: 1, showIf: 'server.loginTimeout.enabled' },
					{
						path: 'server.strictLoginPacketSequence',
						label: 'Reject logins with packets out of order',
						help: 'Turn off if an unusual client can’t join.'
					}
				]
			},
			{
				id: 'packetRateLimiter',
				title: 'Packet rate limit',
				description: 'Disconnect players who send too many packets, which protects the account from misbehaving clients.',
				toggle: 'server.packetRateLimiter.enabled',
				advanced: true,
				fields: [
					{ path: 'server.packetRateLimiter.maxPacketsPerInterval', label: 'At most', unit: 'packets', min: 1 },
					{ path: 'server.packetRateLimiter.intervalSeconds', label: 'Per', unit: 'seconds', min: 0.1 },
					{ path: 'server.packetRateLimiter.logRate', label: 'Log packet rates' }
				]
			},
			{
				id: 'serverViaversion',
				title: 'Player version translation',
				description: 'Let players join the proxy on a different Minecraft version (ViaVersion).',
				toggle: 'server.viaversion.enabled',
				advanced: true,
				fields: [
					{
						path: 'server.viaversion.autoRemoveFromPipeline',
						label: 'Skip translation for players on the same version',
						help: 'Saves work when no translation is needed.'
					}
				]
			},
			{
				id: 'serverPing',
				title: 'Server list entry',
				description: 'What players see for the proxy in their server list.',
				toggle: 'server.ping.enabled',
				advanced: true,
				fields: [
					{ path: 'server.ping.onlinePlayerCount', label: 'Show the player count' },
					{ path: 'server.ping.onlinePlayers', label: 'Show who’s online', help: 'Shown when hovering over the player count.' },
					{ path: 'server.ping.maxPlayers', label: 'Maximum players shown', min: 0 },
					{ path: 'server.updateServerIcon', label: 'Use the account’s skin as the icon' },
					{ path: 'server.ping.responseCaching', label: 'Cache the response' },
					{
						path: 'server.ping.responseCacheSeconds',
						label: 'Cache for',
						unit: 'seconds',
						min: 1,
						showIf: 'server.ping.responseCaching'
					},
					{ path: 'server.ping.logPings', label: 'Log server list pings' }
				]
			},
			{
				id: 'backgroundChecks',
				title: 'Background checks',
				description: 'Things the proxy checks and refreshes on its own.',
				advanced: true,
				fields: [
					{ path: 'server.queueStatusRefreshMinutes', label: 'Refresh 2b2t’s queue length every', unit: 'minutes', min: 1 },
					{ path: 'server.queueStatusRefreshWhileNotOn2b2t', label: 'Also refresh it on other servers' },
					{ path: 'server.dynamicQueueEtaEquation', label: 'Improve queue time estimates over time' },
					{
						path: 'server.playerListsRefreshIntervalMins',
						label: 'Refresh player names every',
						unit: 'minutes',
						min: 10,
						help: 'Updates names in the friends and other player lists after renames.'
					},
					{ path: 'client.extra.tpsBufferSize', label: 'Average the server’s TPS over', unit: 'seconds', min: 1 },
					{
						path: 'server.healthCheck',
						label: 'Restart the proxy’s server if it stops',
						help: 'Checks that players can still connect.'
					}
				]
			}
		]
	},
	{
		id: 'troubleshooting',
		title: 'Troubleshooting',
		icon: Wrench,
		sections: [
			{
				id: 'logging',
				title: 'Logging',
				description: 'What the instance writes to its logs.',
				advanced: true,
				fields: [
					{ path: 'debug.debugLogs', label: 'Write a debug log' },
					{ path: 'debug.terminalDebugLogs', label: 'Debug messages in the container log', showIf: 'debug.debugLogs' },
					{ path: 'debug.inputManagerDebugLogs', label: 'Log movement and click decisions' },
					{ path: 'client.extra.logChatMessages', label: 'Log chat messages' },
					{
						path: 'client.extra.logOnlyQueuePositionUpdates',
						label: 'In the queue, only log position changes',
						showIf: 'client.extra.logChatMessages'
					},
					{ path: 'debug.clearOldLogs', label: 'Delete old log files on start' }
				]
			},
			{
				id: 'packetLog',
				title: 'Packet log',
				description: 'Log the Minecraft packets passing through the proxy.',
				toggle: 'debug.packetLog.enabled',
				advanced: true,
				fields: [
					{
						path: 'debug.packetLog.packetFilterList',
						label: 'Only packets named',
						placeholder: 'chat',
						help: 'Parts of packet names. Empty logs every packet.'
					},
					{ path: 'debug.packetLog.logLevelDebug', label: 'Log to the debug log only' },
					...packetLog('client', 'Server'),
					...packetLog('server', 'Players')
				]
			},
			{
				id: 'workarounds',
				title: 'Workarounds',
				description: 'Fixes and behaviors for specific problems. Leave these alone unless something is wrong.',
				advanced: true,
				fields: [
					{
						path: 'debug.kickDisconnect',
						label: 'Disconnect by getting kicked',
						help: 'Asks the server to kick the account instead of logging out normally.'
					},
					{ path: 'debug.passthroughResourcePacks', label: 'Pass resource packs to the player' },
					{ path: 'debug.inventorySyncOnLogin', label: 'Resync the inventory when someone joins' },
					{ path: 'debug.inventoryRequestServerSyncOnAction', label: 'Resync the inventory after each item move' },
					{ path: 'debug.botRotateBeforeInteract', label: 'Turn towards blocks before using them' },
					{ path: 'debug.botPitchPrecisionClamping', label: 'Round look angles like the vanilla client' },
					{ path: 'debug.entityPushing', label: 'Get pushed by other entities' },
					{ path: 'debug.botAutoExitBed', label: 'Get out of bed automatically' },
					{ path: 'debug.chainBreakSpeed2b2tFix', label: 'Fix chain breaking speed on 2b2t' },
					{ path: 'debug.server.cache.fullbrightChunkSkylight', label: 'Show cached chunks with full daylight' },
					{ path: 'debug.server.cache.fullbrightChunkBlocklight', label: 'Show cached chunks fully lit' },
					{
						path: 'debug.server.cache.maxCachedMaps',
						label: 'Remember up to',
						unit: 'maps',
						min: 0,
						help: 'Each map takes about 16 KB.'
					}
				]
			}
		]
	}
];

export const curatedPaths = new Set(
	catalog.flatMap((g) => g.sections.flatMap((s) => [...(s.toggle ? [s.toggle] : []), ...s.fields.map((f) => f.path)]))
);

/** "client.extra.autoEat.healthThreshold" → "Health threshold" */
export function humanize(path: string) {
	const last = path.split('.').at(-1) ?? path;
	const words = last.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
	return words.charAt(0).toUpperCase() + words.slice(1);
}

/** "RANDOM_OFFSET" → "Random offset" */
export function humanizeEnum(value: string) {
	const words = value.toLowerCase().replace(/_/g, ' ');
	return words.charAt(0).toUpperCase() + words.slice(1);
}
