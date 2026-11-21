<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { capabilityLabels } from '#lib/access.js';
	import CopyField from './CopyField.svelte';
	import Modal from './Modal.svelte';
	import OwnerBadge from './OwnerBadge.svelte';
	import type { Capability, Integration } from '#lib/types.js';

	let { onclose, oncreated }: { onclose: () => void; oncreated: () => void } = $props();

	/** What a token can get, in the order the form shows them. VIEW is always included. */
	const grantable: { cap: Capability; hint: string }[] = [
		{ cap: 'PEARLS', hint: 'Pull any pearl on the bot’s pearl list' },
		{ cap: 'CHAT', hint: 'Say and whisper as the bot' },
		{ cap: 'PLAY', hint: 'Walk, mine, click and use the inventory' },
		{ cap: 'CONTROL', hint: 'Connect, disconnect, requeue, kick and flows' },
		{ cap: 'COMMANDS', hint: 'Run any ZenithProxy command' },
		{ cap: 'SETTINGS', hint: 'Change settings, like waypoints' }
	];

	const instances = $derived(app.overview?.instances ?? []);
	let name = $state('Blockbot');
	let allInstances = $state(false);
	let picked = $state<string[]>([]);
	let caps = $state<Capability[]>(['PEARLS', 'CHAT', 'PLAY', 'CONTROL']);
	let saving = $state(false);
	let error = $state('');
	let created = $state<Integration | null>(null);

	const valid = $derived(name.trim().length > 0 && (allInstances || picked.length > 0));

	async function save(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		error = '';
		try {
			created = await api.post<Integration>('/me/integrations', { name: name.trim(), allInstances, instances: picked, capabilities: caps });
			oncreated();
		} catch (e) {
			error = errorMessage(e);
		} finally {
			saving = false;
		}
	}
</script>

<Modal title={created ? 'Token created' : 'New integration token'} {onclose}>
	{#if created?.token}
		<div class="space-y-4">
			<p class="text-sm text-zinc-300">Copy it now. ZenithHub only keeps a fingerprint of it, so it can’t show it again.</p>
			<CopyField value={created.token} />
			<p class="text-xs text-zinc-400">
				Paste it into the other program, for example Blockbot’s ZenithHub settings, together with this page’s address. Anyone who has it can do what you
				ticked, so treat it like a password. Delete it here when you stop using it.
			</p>
			<button class="btn-primary w-full" onclick={onclose}>Done</button>
		</div>
	{:else}
		<form onsubmit={save} class="space-y-5">
			<div>
				<label class="label" for="integration-name">Name</label>
				<input id="integration-name" class="input" bind:value={name} maxlength="40" placeholder="Blockbot" />
				<p class="mt-1 text-xs text-zinc-400">So you know which program uses it.</p>
			</div>

			<fieldset>
				<legend class="label">Bots it can use</legend>
				<label class="flex items-center gap-2 py-1 text-sm text-zinc-300">
					<input type="checkbox" class="size-4 accent-emerald-600" bind:checked={allInstances} /> All my bots, including ones I add later
				</label>
				{#if !allInstances}
					<div class="mt-1 max-h-56 space-y-0.5 overflow-y-auto rounded-lg border border-zinc-800 p-2">
						{#each instances as i (i.id)}
							<label class="flex items-center gap-2 rounded px-1 py-1.5 text-sm text-zinc-200 hover:bg-zinc-800/60">
								<input type="checkbox" class="size-4 accent-emerald-600" value={i.id} bind:group={picked} />
								<span class="min-w-0 flex-1 truncate">{i.name} <span class="text-zinc-500">· {i.accountName}</span></span>
								<OwnerBadge mine={i.mine} ownerName={i.ownerName} />
							</label>
						{:else}
							<p class="px-1 py-2 text-sm text-zinc-500">You don’t have any bots yet.</p>
						{/each}
					</div>
				{/if}
			</fieldset>

			<fieldset>
				<legend class="label">What it may do</legend>
				<p class="mb-2 text-xs text-zinc-400">
					It can always {capabilityLabels.VIEW.toLowerCase()}. It never gets more than you have, so on a friend’s bot it only gets what they gave you.
				</p>
				<div class="space-y-1">
					{#each grantable as g (g.cap)}
						<label class="flex items-start gap-2 py-1 text-sm">
							<input type="checkbox" class="mt-0.5 size-4 accent-emerald-600" value={g.cap} bind:group={caps} />
							<span>
								<span class="text-zinc-200">{capabilityLabels[g.cap]}</span>
								<span class="block text-xs text-zinc-500">{g.hint}</span>
							</span>
						</label>
					{/each}
				</div>
			</fieldset>

			{#if error}<p class="text-sm text-red-400">{error}</p>{/if}
			<button class="btn-primary w-full" disabled={saving || !valid}>Create token</button>
		</form>
	{/if}
</Modal>
