<script lang="ts">
	import { api } from '#lib/api.js';
	import Modal from '#lib/components/Modal.svelte';
	import { attempt } from '#lib/toast.svelte.js';
	import type { StagedPlugin } from '#lib/types.js';
	import { Bot, Globe, Server, ShieldAlert } from '@lucide/svelte';

	let { staged, onclose, oninstalled }: { staged: StagedPlugin; onclose: () => void; oninstalled: () => void } = $props();
	const p = $derived(staged.plugin);
	let busy = $state(false);
	const mixins = (n: number) => (n === 1 ? '1 mixin' : `${n} mixins`);

	async function install() {
		busy = true;
		const done = await attempt(
			() => api.post(`/plugins/staged/${staged.token}/install`, { enable: true }),
			staged.replaces ? `${p.name} updated` : `${p.name} installed`
		);
		busy = false;
		if (done) oninstalled();
	}
</script>

<Modal title={staged.replaces ? 'Update plugin' : 'Install plugin'} {onclose}>
	<div class="flex flex-col gap-4">
		<div>
			<div class="flex flex-wrap items-baseline gap-x-2">
				<span class="text-lg font-semibold">{p.name}</span>
				<span class="text-sm text-zinc-500">v{p.version}</span>
			</div>
			{#if staged.replaces}<div class="text-xs text-sky-300">Replaces v{staged.replaces}, settings and data are kept</div>{/if}
			{#if p.authors.length}<div class="text-xs text-zinc-500">by {p.authors.join(', ')}</div>{/if}
			{#if p.description}<p class="mt-2 text-sm text-zinc-300">{p.description}</p>{/if}
		</div>

		<div>
			<div class="label mb-1.5">What it changes</div>
			<ul class="flex flex-col gap-2 text-sm">
				{#if p.parts.manager.present}
					<li class="flex gap-2.5">
						<Server size={16} class="mt-0.5 shrink-0 text-zinc-400" />
						<span
							>The ZenithHub manager{p.parts.manager.mixins ? `, with ${mixins(p.parts.manager.mixins)} into its code` : ''}.
							<span class="text-zinc-500">Loads after a restart of ZenithHub.</span></span
						>
					</li>
				{/if}
				{#if p.parts.bot.present}
					<li class="flex gap-2.5">
						<Bot size={16} class="mt-0.5 shrink-0 text-zinc-400" />
						<span
							>Every bot{p.parts.bot.mixins ? `, with ${mixins(p.parts.bot.mixins)} into ZenithProxy` : ''}.
							<span class="text-zinc-500">Bots run on Java while a plugin needs them, and load it when they restart.</span></span
						>
					</li>
				{/if}
				{#if p.parts.web.present}
					<li class="flex gap-2.5">
						<Globe size={16} class="mt-0.5 shrink-0 text-zinc-400" />
						<span>This web UI, for everyone who signs in. <span class="text-zinc-500">Loads when the page reloads.</span></span>
					</li>
				{/if}
			</ul>
		</div>

		<div class="flex gap-3 rounded-lg border border-amber-800/60 bg-amber-950/40 p-3 text-sm text-amber-100">
			<ShieldAlert size={18} class="mt-0.5 shrink-0 text-amber-400" />
			<div>
				Plugins aren't sandboxed. This one can read every account's sign-in, every password hash and every setting, and act as any user. Only install
				it if you trust where it came from.
			</div>
		</div>

		<div class="flex justify-end gap-2">
			<button class="btn-secondary" onclick={onclose}>Cancel</button>
			<button class="btn-primary" disabled={busy} onclick={install}>{staged.replaces ? 'Update' : 'Install'}</button>
		</div>
	</div>
</Modal>
