import type { Component } from 'svelte';
import {
	Bell,
	Bot,
	MapPin,
	Cable,
	Heart,
	MessageSquare,
	Shield,
	Sparkles,
	Swords,
	Users
} from '@lucide/svelte';

/** One setting in the curated settings UI. */
export interface Field {
	path: string;
	label: string;
	help?: string;
	/** only shown while this boolean setting is on, or while the predicate holds */
	showIf?: string | ((values: Record<string, unknown>) => boolean);
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
	fields: Field[];
}

export interface Group {
	id: string;
	title: string;
	icon: Component;
	sections: Section[];
}

const TICKS = 'ticks (20 = 1s)';

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
				id: 'proxy',
				title: 'Outbound proxy',
				description: 'Connect to the server through a SOCKS or HTTP proxy instead of this machine’s IP.',
				toggle: 'client.connectionProxy.enabled',
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
					{ path: 'client.extra.autoEat.warning', label: 'Warn when out of food' }
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
					{ path: 'client.extra.autoTotem.noTotemsAlert', label: 'Alert when out of totems' }
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
				fields: []
			},
			{
				id: 'autoMend',
				title: 'Auto mend',
				description: 'Swap damaged Mending gear into the hands to repair it with XP.',
				toggle: 'client.extra.autoMend.enabled',
				fields: []
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
					}
				]
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
					{ path: 'client.extra.coordObfuscation.exemptProxyAccount', label: 'Show real coordinates to the account owner' }
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
					{ path: 'client.extra.actionLimiter.allowBlockBreaking', label: 'Allow breaking blocks' },
					{ path: 'client.extra.actionLimiter.allowInteract', label: 'Allow using blocks and entities' },
					{ path: 'client.extra.actionLimiter.allowInventory', label: 'Allow inventory changes' },
					{ path: 'client.extra.actionLimiter.allowEnderChest', label: 'Allow opening ender chests' },
					{ path: 'client.extra.actionLimiter.allowUseItem', label: 'Allow using items' },
					{ path: 'client.extra.actionLimiter.allowChat', label: 'Allow chatting' },
					{ path: 'client.extra.actionLimiter.allowServerCommands', label: 'Allow server commands' },
					{ path: 'client.extra.actionLimiter.allowRespawn', label: 'Allow respawning' },
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
					{ path: 'client.extra.antiafk.actions.swingHand', label: 'Swing arm' },
					{ path: 'client.extra.antiafk.actions.rotate', label: 'Look around' },
					{ path: 'client.extra.antiafk.actions.jump', label: 'Jump' },
					{ path: 'client.extra.antiafk.actions.jumpOnlyInLiquid', label: 'Only jump in water or lava', showIf: 'client.extra.antiafk.actions.jump' },
					{ path: 'client.extra.antiafk.actions.sneak', label: 'Sneak' }
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
					{ path: 'client.extra.killAura.attackDelayTicks', label: 'Attack delay', unit: TICKS, min: 0 }
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
					{ path: 'client.extra.chat.ignoreList', label: 'Ignored players' }
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
					{ path: 'client.extra.chat.suffix', label: 'Suffix', showIf: 'client.extra.chat.suffixChats' }
				]
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
					{ path: 'server.spectator.spectatorPublicChatEnabled', label: 'Spectators can chat publicly' },
					{ path: 'server.spectator.playerCamOnJoin', label: 'Start in first person view' }
				]
			},
			{
				id: 'joinOptions',
				title: 'When you join',
				description: 'In-game behavior while you’re playing through the proxy.',
				fields: [
					{ path: 'server.welcomeMessages', label: 'Show welcome messages' },
					{ path: 'server.preferLoginAsController', label: 'Take control when joining', help: 'Otherwise you join as a spectator if someone is playing.' },
					{ path: 'server.extra.chatHistory.enable', label: 'Show recent chat when joining' },
					{ path: 'inGameCommands.enable', label: 'In-game commands' },
					{ path: 'inGameCommands.prefix', label: 'Command prefix', showIf: 'inGameCommands.enable' }
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
					{ path: 'client.extra.pearlLoader.returnToStartPos', label: 'Walk back afterwards' }
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
				fields: [{ path: 'client.extra.queueWarning.warningPositions', label: 'Alert at positions' }]
			},
			{
				id: 'autoFish',
				title: 'Auto fish',
				description: 'Fish automatically. Stand facing water with a rod in hand.',
				toggle: 'client.extra.autoFish.enabled',
				fields: [
					{ path: 'client.extra.autoFish.yaw', label: 'Look direction (yaw)' },
					{ path: 'client.extra.autoFish.pitch', label: 'Look angle (pitch)', min: -90, max: 90 }
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
