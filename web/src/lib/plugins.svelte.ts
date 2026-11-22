import { goto } from '$app/navigation';
import { api } from './api';
import { app } from './app.svelte';
import { toasts } from './toast.svelte';
import type { Instance, WebPlugin } from './types';

/**
 * The web side of plugins. Every script a plugin lists in plugin.json is loaded as an ES module once the overview
 * is in; its default export (or `setup`) gets a {@link PluginHub} to add pages, settings pages, instance tabs and
 * dashboard cards. Plugins are trusted code: they can also do anything else a script on the page can.
 * See docs/plugins.md.
 */

/** Cleans up what a mount added; called when the element goes away. */
export type Cleanup = () => void;
type MountResult = void | Cleanup | Promise<void | Cleanup>;

export interface PageContext {
	hub: PluginHub;
	/** what follows the page's path, e.g. "a/b" for /ext/<plugin>/<page>/a/b */
	rest: string;
	url: URL;
}

export interface InstanceContext {
	hub: PluginHub;
	instanceId: string;
	/** the instance as it is when the tab opens; use hub.app.instance(id) for the live one */
	instance: Instance;
}

export interface PageSpec {
	/** /ext/<plugin>/<path> */
	path: string;
	title: string;
	/** SVG markup for the nav, 24×24 viewBox, stroke="currentColor" like the built-in icons */
	icon?: string;
	/** show it in the top bar (and the bottom bar on phones) */
	nav?: boolean;
	/** admins only */
	admin?: boolean;
	mount: (el: HTMLElement, ctx: PageContext) => MountResult;
}

export interface SettingsPageSpec {
	/** /settings/ext/<plugin>/<path> */
	path: string;
	title: string;
	description?: string;
	icon?: string;
	admin?: boolean;
	mount: (el: HTMLElement, ctx: PageContext) => MountResult;
}

export interface InstanceTabSpec {
	id: string;
	label: string;
	icon?: string;
	/** which instances get it; all by default. Check hub.app.instance(id).capabilities for permissions */
	show?: (instance: Instance) => boolean;
	mount: (el: HTMLElement, ctx: InstanceContext) => MountResult;
}

export interface DashboardCardSpec {
	id: string;
	mount: (el: HTMLElement, ctx: { hub: PluginHub }) => MountResult;
}

type WsMessage = { type: string; instanceId: string | null; data: unknown };

export interface PluginHub {
	plugin: { id: string; name: string; version: string; settings: Record<string, unknown> };
	/** the app's state: session, overview (instances, accounts, servers), devMode… It's reactive. */
	app: typeof app;
	api: typeof api & {
		/** calls the plugin's own bot side in a running instance: GET needs View, other methods Control */
		bot: <T>(instanceId: string, method: string, path: string, body?: unknown) => Promise<T>;
	};
	toast: { success: (message: string) => void; error: (e: unknown) => void; show: typeof toasts.show };
	goto: typeof goto;
	addPage: (spec: PageSpec) => void;
	addSettingsPage: (spec: SettingsPageSpec) => void;
	addInstanceTab: (spec: InstanceTabSpec) => void;
	addDashboardCard: (spec: DashboardCardSpec) => void;
	/** every WebSocket message, or those of one type; returns the unsubscribe function */
	on: (type: string | '*', listener: (msg: WsMessage) => void) => Cleanup;
	/** what this plugin's manager or bot side published: `event` and the instance it's about */
	onEvent: (event: string | '*', listener: (data: unknown, instanceId: string | null, event: string) => void) => Cleanup;
	/** a URL for a file in the plugin's web/ folder */
	asset: (path: string) => string;
}

export type Registered<T> = T & { plugin: string; hub: PluginHub };

class PluginHost {
	pages = $state<Registered<PageSpec>[]>([]);
	settingsPages = $state<Registered<SettingsPageSpec>[]>([]);
	instanceTabs = $state<Registered<InstanceTabSpec>[]>([]);
	dashboardCards = $state<Registered<DashboardCardSpec>[]>([]);
	/** plugin id → why its script didn't load */
	errors = $state<Record<string, string>>({});
	#loaded = new Map<string, string>();
	#styles = new Map<string, HTMLLinkElement[]>();
	#listeners = new Map<string, Cleanup[]>();

	/** Loads the scripts of plugins that are new or changed, and drops what removed ones added. */
	sync(plugins: WebPlugin[]) {
		const wanted = new Map(plugins.map((p) => [p.id, p]));
		for (const id of [...this.#loaded.keys()]) {
			const p = wanted.get(id);
			if (!p || this.#key(p) !== this.#loaded.get(id)) this.#unload(id);
		}
		for (const p of plugins) {
			if (this.#loaded.has(p.id)) continue;
			this.#loaded.set(p.id, this.#key(p));
			void this.#load(p);
		}
	}

	#key(p: WebPlugin) {
		return [...p.scripts, ...p.styles].join('|') + JSON.stringify(p.settings);
	}

	#unload(id: string) {
		this.#loaded.delete(id);
		this.pages = this.pages.filter((x) => x.plugin !== id);
		this.settingsPages = this.settingsPages.filter((x) => x.plugin !== id);
		this.instanceTabs = this.instanceTabs.filter((x) => x.plugin !== id);
		this.dashboardCards = this.dashboardCards.filter((x) => x.plugin !== id);
		this.#styles.get(id)?.forEach((l) => l.remove());
		this.#styles.delete(id);
		this.#listeners.get(id)?.forEach((c) => c());
		this.#listeners.delete(id);
		delete this.errors[id];
	}

	async #load(p: WebPlugin) {
		const links = p.styles.map((href) => {
			const link = document.createElement('link');
			link.rel = 'stylesheet';
			link.href = href;
			link.dataset.plugin = p.id;
			document.head.append(link);
			return link;
		});
		this.#styles.set(p.id, links);
		const hub = this.#hub(p);
		for (const url of p.scripts) {
			try {
				const mod = await import(/* @vite-ignore */ url);
				const setup = mod.default ?? mod.setup;
				if (typeof setup === 'function') await setup(hub);
			} catch (e) {
				console.error(`Plugin ${p.id}: ${url} failed`, e);
				this.errors[p.id] = e instanceof Error ? e.message : String(e);
			}
		}
	}

	#hub(p: WebPlugin): PluginHub {
		const plugin = p.id;
		const listeners = (this.#listeners.get(plugin) ?? []) as Cleanup[];
		this.#listeners.set(plugin, listeners);
		const add = <T>(list: 'pages' | 'settingsPages' | 'instanceTabs' | 'dashboardCards', spec: T, key: (s: T) => string) => {
			// re-adding the same item replaces it, so a script can register again
			const items = (this[list] as unknown as Registered<T>[]).filter((x) => !(x.plugin === plugin && key(x) === key(spec)));
			(this[list] as unknown as Registered<T>[]) = [...items, { ...spec, plugin, hub }];
		};
		const on = (type: string, listener: (msg: WsMessage) => void) => {
			const off = app.onMessage((msg) => {
				if (type === '*' || msg.type === type) listener(msg);
			});
			listeners.push(off);
			return off;
		};
		const hub: PluginHub = {
			plugin: { id: p.id, name: p.name, version: p.version, settings: p.settings },
			app,
			api: {
				...api,
				bot: <T>(instanceId: string, method: string, path: string, body?: unknown) => {
					const url = `/plugins/${plugin}/instances/${encodeURIComponent(instanceId)}/bot/${path.replace(/^\//, '')}`;
					const m = method.toUpperCase();
					if (m === 'GET') return api.get<T>(url);
					if (m === 'POST') return api.post<T>(url, body ?? {});
					if (m === 'PUT') return api.put<T>(url, body ?? {});
					if (m === 'PATCH') return api.patch<T>(url, body ?? {});
					return api.del<T>(url, body ?? {});
				}
			},
			toast: { success: (m) => toasts.success(m), error: (e) => toasts.error(e), show: toasts.show.bind(toasts) },
			goto,
			addPage: (spec) => add('pages', { ...spec, path: clean(spec.path) }, (s) => s.path),
			addSettingsPage: (spec) => add('settingsPages', { ...spec, path: clean(spec.path) }, (s) => s.path),
			addInstanceTab: (spec) => add('instanceTabs', spec, (s) => s.id),
			addDashboardCard: (spec) => add('dashboardCards', spec, (s) => s.id),
			on,
			onEvent: (event, listener) =>
				on('plugin', (msg) => {
					const data = msg.data as { plugin?: string; event?: string; data?: unknown };
					if (data.plugin !== plugin || (event !== '*' && data.event !== event)) return;
					listener(data.data, msg.instanceId, data.event ?? '');
				}),
			asset: (path) => `/plugin-assets/${plugin}/${path.replace(/^\//, '')}`
		};
		return hub;
	}

	/** The page a URL under /ext/<plugin>/ (or /settings/ext/<plugin>/) shows, and what follows its path. */
	find<T extends { path: string; plugin: string }>(list: T[], plugin: string, path: string) {
		const parts = clean(path);
		let best: T | undefined;
		for (const item of list) {
			if (item.plugin !== plugin) continue;
			if (item.path === '' || parts === item.path || parts.startsWith(item.path + '/')) {
				if (!best || item.path.length > best.path.length) best = item;
			}
		}
		return best ? { item: best, rest: parts.slice(best.path.length).replace(/^\//, '') } : null;
	}
}

function clean(path: string) {
	return path.replace(/^\/+|\/+$/g, '');
}

export const plugins = new PluginHost();

/** Where a plugin page lives. */
export function pageHref(item: { plugin: string; path: string }, settings = false) {
	return `${settings ? '/settings' : ''}/ext/${item.plugin}${item.path ? '/' + item.path : ''}`;
}
