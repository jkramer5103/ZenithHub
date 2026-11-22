// The web side: an ES module the browser loads for every signed-in user. Its default export gets the hub.
const icon =
	'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 11V6a2 2 0 0 0-4 0v5"/><path d="M14 10V4a2 2 0 0 0-4 0v2"/><path d="M10 10.5V6a2 2 0 0 0-4 0v8"/><path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"/></svg>';

const h = (tag, attrs = {}, ...children) => {
	const el = document.createElement(tag);
	for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
	el.append(...children);
	return el;
};

export default function setup(hub) {
	const { greeting, color } = hub.plugin.settings;

	hub.addPage({
		path: '',
		title: 'Hello',
		icon,
		nav: true,
		async mount(el) {
			el.append(h('h1', { class: 'text-2xl font-semibold mb-4' }, `${greeting}, ${hub.app.user.username}!`));
			const card = h('div', { class: 'card p-4 text-sm hello-card' }, 'Loading…');
			el.append(card);
			const summary = await hub.api.get('/plugins/hello/summary');
			card.replaceChildren(
				h('div', {}, `You can see ${hub.app.overview.instances.length} of ${summary.instances} instances.`),
				h('div', { class: 'text-zinc-400 mt-1' }, `Chat messages seen since ZenithHub started: ${JSON.stringify(summary.chatMessages)}`),
				h('div', { class: 'text-zinc-500 mt-1' }, `Mixin in the manager: ${hub.app.overview.helloMixin ?? 'not applied'}`)
			);
		}
	});

	hub.addSettingsPage({
		path: 'about',
		title: 'Hello',
		description: 'An example of a settings page from a plugin',
		icon,
		mount(el) {
			el.append(h('h1', { class: 'text-2xl font-semibold mb-2' }, 'Hello plugin'), h('p', { class: 'text-sm text-zinc-400' }, 'Plugins can add pages under Settings too.'));
		}
	});

	hub.addInstanceTab({
		id: 'hello',
		label: 'Hello',
		icon,
		show: (instance) => instance.capabilities.includes('VIEW'),
		mount(el, { instanceId }) {
			const out = h('div', { class: 'card p-4 text-sm' }, 'Ask the bot…');
			const button = h('button', { class: 'btn-secondary mb-3' }, 'Ask the bot');
			const log = h('ul', { class: 'mt-3 text-xs text-zinc-400' });
			button.onclick = async () => {
				try {
					const stats = await hub.api.bot(instanceId, 'GET', 'stats');
					out.textContent = `${stats.greeting}. Chat messages: ${stats.chatMessages}, TPS ${stats.tps}`;
				} catch (e) {
					hub.toast.error(e);
				}
			};
			el.append(button, out, log);
			// live events from this plugin's bot side
			return hub.onEvent('chat', (data, id) => {
				if (id === instanceId) log.prepend(h('li', {}, data.message));
			});
		}
	});

	hub.addDashboardCard({
		id: 'hello',
		mount(el) {
			el.append(h('div', { class: `hello-card hello-${color} font-medium` }, `${greeting} from a plugin`), h('div', { class: 'text-xs text-zinc-500' }, 'This card comes from the Hello example plugin.'));
		}
	});
}
