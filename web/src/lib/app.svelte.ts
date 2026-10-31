import { api, setUnauthorizedHandler } from './api';
import type { LogLine, Overview, PendingLogin, Runtime, Session } from './types';
import { toasts } from './toast.svelte';

type LogListener = (line: LogLine) => void;
type LoginListener = (login: PendingLogin) => void;

/**
 * Global client state: the session, the overview (instances, accounts, servers),
 * and one WebSocket that keeps it live.
 */
class AppState {
	session = $state<Session | null>(null);
	overview = $state<Overview | null>(null);
	/** false while the WebSocket is reconnecting; the UI shows a banner */
	live = $state(true);
	/** shows the raw console and every setting; off by default so nobody needs commands */
	devMode = $state(typeof localStorage !== 'undefined' && localStorage.getItem('devMode') === '1');

	#ws: WebSocket | null = null;
	#reconnectDelay = 1000;
	#reconnectTimer: ReturnType<typeof setTimeout> | undefined;
	#logListeners = new Map<string, Set<LogListener>>();
	#loginListeners = new Set<LoginListener>();
	#reconnectListeners = new Set<() => void>();
	#refetchQueued = false;

	constructor() {
		setUnauthorizedHandler(() => {
			if (this.session?.user) this.#loggedOut();
		});
	}

	setDevMode(on: boolean) {
		this.devMode = on;
		localStorage.setItem('devMode', on ? '1' : '0');
	}

	get user() {
		if (!this.session?.user) return null;
		// the overview is refetched on every change, so linked accounts show up right away
		return this.overview?.user ?? this.session.user;
	}

	get isAdmin() {
		return this.session?.user?.admin ?? false;
	}

	async loadSession() {
		this.session = await api.prefetched<Session>('/session');
		if (this.session.user) await this.#loggedIn();
	}

	async login(username: string, password: string, setup: boolean) {
		await api.post(setup ? '/setup' : '/login', { username, password });
		await this.loadSession();
	}

	async logout() {
		await api.post('/logout').catch(() => {});
		this.#loggedOut();
	}

	#refreshes = 0;
	#newest: Promise<void> = Promise.resolve();

	async refresh() {
		// overlapping refreshes (WebSocket and a dialog) can finish out of order; keep the newest,
		// and only resolve once the newest has landed so callers act on fresh data
		const request = ++this.#refreshes;
		let pending = (this.#newest = api.get<Overview>('/overview').then((overview) => {
			if (request === this.#refreshes) this.overview = overview;
		}));
		await pending;
		while (pending !== this.#newest) await (pending = this.#newest);
	}

	instance(id: string) {
		return this.overview?.instances.find((i) => i.id === id);
	}

	/** Subscribes to live log lines of an instance; returns the unsubscribe function. */
	onLog(instanceId: string, listener: LogListener) {
		let set = this.#logListeners.get(instanceId);
		if (!set) this.#logListeners.set(instanceId, (set = new Set()));
		set.add(listener);
		this.#sendSubscriptions();
		return () => {
			set.delete(listener);
			if (set.size === 0) this.#logListeners.delete(instanceId);
			this.#sendSubscriptions();
		};
	}

	onLogin(listener: LoginListener) {
		this.#loginListeners.add(listener);
		return () => this.#loginListeners.delete(listener);
	}

	/** Called after the WebSocket reconnects, so views can refetch what they may have missed. */
	onReconnect(listener: () => void) {
		this.#reconnectListeners.add(listener);
		return () => this.#reconnectListeners.delete(listener);
	}

	async #loggedIn() {
		this.overview = await api.prefetched<Overview>('/overview');
		this.#connect();
	}

	#loggedOut() {
		clearTimeout(this.#reconnectTimer);
		const ws = this.#ws;
		this.#ws = null;
		ws?.close();
		this.overview = null;
		this.session = { setupRequired: false, user: null };
	}

	#connect() {
		if (this.#ws) return;
		const proto = location.protocol === 'https:' ? 'wss' : 'ws';
		const ws = new WebSocket(`${proto}://${location.host}/api/ws`);
		this.#ws = ws;
		let opened = false;
		ws.onopen = () => {
			opened = true;
			const wasDown = !this.live;
			this.live = true;
			this.#reconnectDelay = 1000;
			this.#sendSubscriptions();
			if (wasDown) {
				this.refresh().catch(() => {});
				this.#reconnectListeners.forEach((l) => l());
			}
		};
		ws.onmessage = (e) => this.#handle(JSON.parse(e.data));
		ws.onclose = (e) => {
			if (this.#ws !== ws) return; // closed on purpose
			this.#ws = null;
			if (e.code === 4001) {
				this.#loggedOut();
				return;
			}
			if (opened || !this.live) this.live = false;
			this.#reconnectTimer = setTimeout(() => this.#connect(), this.#reconnectDelay);
			this.#reconnectDelay = Math.min(this.#reconnectDelay * 2, 15000);
		};
	}

	#sendSubscriptions() {
		if (this.#ws?.readyState === WebSocket.OPEN) {
			this.#ws.send(JSON.stringify({ subscribe: [...this.#logListeners.keys()] }));
		}
	}

	#handle(msg: { type: string; instanceId: string | null; data: unknown }) {
		switch (msg.type) {
			case 'runtime': {
				const instance = msg.instanceId ? this.instance(msg.instanceId) : undefined;
				if (instance) instance.runtime = msg.data as Runtime;
				break;
			}
			case 'log':
				this.#logListeners.get(msg.instanceId ?? '')?.forEach((l) => l(msg.data as LogLine));
				break;
			case 'login':
				this.#loginListeners.forEach((l) => l(msg.data as PendingLogin));
				break;
			case 'alert': {
				// a task's "Notify me"; only the instance's owner gets these
				const text = (msg.data as { text?: string }).text ?? '';
				const name = (msg.instanceId && this.instance(msg.instanceId)?.name) || 'ZenithHub';
				toasts.show('alert', text, name);
				// the system notification needs https or localhost, and permission from the Tasks tab
				if (typeof Notification !== 'undefined' && Notification.permission === 'granted' && document.hidden) {
					try {
						new Notification(name, { body: text, tag: `alert-${msg.instanceId}-${text}` });
					} catch {
						// some mobile browsers only allow notifications from a service worker
					}
				}
				break;
			}
			case 'changed':
				// bursts of changes collapse into one refetch
				if (this.#refetchQueued) break;
				this.#refetchQueued = true;
				setTimeout(() => {
					this.#refetchQueued = false;
					this.refresh().catch(() => {});
				}, 100);
				break;
		}
	}
}

export const app = new AppState();
