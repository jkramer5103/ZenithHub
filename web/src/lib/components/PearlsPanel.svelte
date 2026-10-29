<script lang="ts">
	import { api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { runAction } from '#lib/actions.js';
	import CopyField from '#lib/components/CopyField.svelte';
	import Switch from '#lib/components/settings/Switch.svelte';
	import { attempt, toasts } from '#lib/toast.svelte.js';
	import type { Capability, InstanceStatus, Pearl, SettingsView } from '#lib/types.js';
	import { Link2, LocateFixed, Pencil, Plus, RefreshCw, Trash2, Webhook, X } from '@lucide/svelte';

	let {
		instanceId,
		capabilities,
		status
	}: { instanceId: string; capabilities: Capability[]; status: InstanceStatus | null } = $props();

	const PEARLS = 'client.extra.pearlLoader.pearls';
	const RETURN = 'client.extra.pearlLoader.returnToStartPos';
	const DOUBLE = 'client.extra.pearlLoader.doubleClick';

	const canEdit = $derived(capabilities.includes('SETTINGS'));
	let pearls = $state<Pearl[] | null>(null);
	let settings = $state<SettingsView | null>(null);
	let loading = $state<string | null>(null);

	async function load() {
		try {
			pearls = await api.get<Pearl[]>(`/instances/${instanceId}/pearls`);
			if (canEdit) settings = await api.get<SettingsView>(`/instances/${instanceId}/settings`);
		} catch (e) {
			toasts.error(e);
			pearls ??= [];
		}
	}
	$effect(() => {
		void load();
	});

	async function loadPearl(id: string) {
		loading = id;
		await runAction(instanceId, { action: 'loadPearl', pearl: id });
		loading = null;
	}

	// --- editing: the list is the PearlLoader setting ---
	let editing = $state<{ original: string | null; id: string; x: string; y: string; z: string } | null>(null);
	const idValid = $derived(!!editing && /^[A-Za-z0-9_.-]{1,32}$/.test(editing.id.trim()));
	const coordsValid = $derived(!!editing && [editing.x, editing.y, editing.z].every((v) => /^-?\d+$/.test(v.trim())));
	const duplicate = $derived(
		!!editing && !!pearls?.some((p) => p.id.toLowerCase() === editing!.id.trim().toLowerCase() && p.id !== editing!.original)
	);

	function startAdd() {
		editing = { original: null, id: app.user?.mcNames[0] ?? '', x: '', y: '', z: '' };
	}

	function startEdit(p: Pearl) {
		editing = { original: p.id, id: p.id, x: String(p.x), y: String(p.y), z: String(p.z) };
	}

	async function savePearls(list: { id: string; x: number; y: number; z: number }[], message: string) {
		return attempt(async () => {
			await api.patch(`/instances/${instanceId}/settings`, { set: { [PEARLS]: list } });
			await load();
		}, message);
	}

	async function saveEdit() {
		if (!editing || !pearls) return;
		const entry = { id: editing.id.trim(), x: Number(editing.x), y: Number(editing.y), z: Number(editing.z) };
		const list = pearls.map(({ id, x, y, z }) => ({ id, x, y, z }));
		const index = list.findIndex((p) => p.id === editing!.original);
		if (index >= 0) list[index] = entry;
		else list.push(entry);
		if (await savePearls(list, editing.original ? 'Pearl saved' : 'Pearl added')) editing = null;
	}

	async function remove(p: Pearl) {
		if (!pearls || !confirm(`Remove ${p.id}'s pearl from the list?`)) return;
		await savePearls(
			pearls.filter((q) => q.id !== p.id).map(({ id, x, y, z }) => ({ id, x, y, z })),
			'Pearl removed'
		);
	}

	function useBotPosition() {
		if (!editing || !status?.position) return;
		editing.x = String(Math.floor(status.position.x));
		editing.y = String(Math.floor(status.position.y));
		editing.z = String(Math.floor(status.position.z));
	}

	async function setOption(path: string, value: boolean) {
		await attempt(async () => {
			await api.patch(`/instances/${instanceId}/settings`, { set: { [path]: value } });
			settings = await api.get<SettingsView>(`/instances/${instanceId}/settings`);
		});
	}

	// --- webhooks, in developer mode ---
	let webhookFor = $state<string | null>(null);
	let webhookUrl = $state<string | null>(null);

	async function showWebhook(pearl: string, replace = false) {
		if (replace && !confirm('Make a new URL? The old one stops working.')) return;
		await attempt(async () => {
			const { path } = await api.post<{ path: string }>(`/instances/${instanceId}/pearls/${encodeURIComponent(pearl)}/webhook`, { replace });
			webhookFor = pearl;
			webhookUrl = `${window.location.origin}${path}`;
			if (pearls) pearls = pearls.map((p) => (p.id === pearl ? { ...p, hasWebhook: true } : p));
		}, replace ? 'New URL made, the old one no longer works' : undefined);
	}

	async function revokeWebhook(pearl: string) {
		if (!confirm('Turn this URL off? Anything using it stops working.')) return;
		await attempt(async () => {
			await api.del(`/instances/${instanceId}/pearls/${encodeURIComponent(pearl)}/webhook`);
			webhookFor = null;
			webhookUrl = null;
			if (pearls) pearls = pearls.map((p) => (p.id === pearl ? { ...p, hasWebhook: false } : p));
		}, 'URL turned off');
	}
</script>

<div class="mx-auto w-full max-w-3xl space-y-4">
	<section class="card">
		<div class="flex items-center gap-2 border-b border-zinc-800 px-4 py-3">
			<div class="flex-1">
				<h2 class="text-sm font-semibold text-zinc-200">Stasis pearls</h2>
				<p class="text-xs text-zinc-500">Load a pearl and the bot walks over and flips its trapdoor, so its owner teleports there.</p>
			</div>
			{#if canEdit && !editing}
				<button class="btn-secondary shrink-0 py-1.5" onclick={startAdd}><Plus size={15} /> Add</button>
			{/if}
		</div>

		{#if editing}
			<form
				class="space-y-3 border-b border-zinc-800 bg-zinc-950/40 px-4 py-4"
				onsubmit={(e) => {
					e.preventDefault();
					if (idValid && coordsValid && !duplicate) saveEdit();
				}}
			>
				<div class="flex items-center">
					<h3 class="flex-1 text-sm font-medium text-zinc-200">{editing.original ? `Edit ${editing.original}'s pearl` : 'Add a pearl'}</h3>
					<button type="button" class="btn-ghost p-1.5" aria-label="Cancel" onclick={() => (editing = null)}><X size={16} /></button>
				</div>
				<label class="block">
					<span class="label">Name</span>
					<input class="input" placeholder="Usually the player whose pearl it is" maxlength="32" autocapitalize="off" spellcheck="false" bind:value={editing.id} />
					{#if editing.id.trim() && !idValid}
						<span class="mt-1 block text-xs text-red-300">Letters, numbers, _ . and - only, no spaces.</span>
					{:else if duplicate}
						<span class="mt-1 block text-xs text-red-300">There's already a pearl with that name.</span>
					{/if}
				</label>
				<div>
					<span class="label">Trapdoor position</span>
					<div class="grid grid-cols-3 gap-2">
						<input class="input" placeholder="X" inputmode="numeric" aria-label="X" bind:value={editing.x} />
						<input class="input" placeholder="Y" inputmode="numeric" aria-label="Y" bind:value={editing.y} />
						<input class="input" placeholder="Z" inputmode="numeric" aria-label="Z" bind:value={editing.z} />
					</div>
					<p class="mt-1 text-xs text-zinc-500">The block the bot clicks: the trapdoor, or a lever or button that closes it.</p>
				</div>
				<div class="flex flex-wrap gap-2">
					<button class="btn-primary" disabled={!idValid || !coordsValid || duplicate}>{editing.original ? 'Save' : 'Add pearl'}</button>
					{#if status?.position}
						<button type="button" class="btn-ghost" onclick={useBotPosition}><LocateFixed size={15} /> Use the bot's position</button>
					{/if}
				</div>
			</form>
		{/if}

		{#if pearls === null}
			<p class="px-4 py-6 text-sm text-zinc-500">Loading…</p>
		{:else if !pearls.length}
			<div class="px-4 py-8 text-center text-sm text-zinc-400">
				No pearls yet.
				{#if canEdit}Add one with its trapdoor's coordinates.{:else}Someone who can change settings has to add them.{/if}
			</div>
		{:else}
			<ul class="divide-y divide-zinc-800">
				{#each pearls as p (p.id)}
					<li class="px-4 py-3">
						<div class="flex items-center gap-3">
							<img
								src="https://mc-heads.net/avatar/{p.id}/32"
								alt=""
								class="size-8 shrink-0 rounded bg-zinc-800 [image-rendering:pixelated]"
								onerror={(e) => ((e.currentTarget as HTMLImageElement).style.visibility = 'hidden')}
							/>
							<div class="min-w-0 flex-1">
								<div class="truncate font-medium text-zinc-100">{p.id}</div>
								<div class="text-xs text-zinc-500">{p.x}, {p.y}, {p.z}</div>
							</div>
							{#if app.devMode}
								<button
									class="btn-ghost p-2 {p.hasWebhook ? 'text-emerald-400' : ''}"
									title="Webhook URL"
									aria-label="Webhook URL for {p.id}"
									onclick={() => (webhookFor === p.id ? (webhookFor = null) : showWebhook(p.id))}
								>
									<Webhook size={16} />
								</button>
							{/if}
							{#if canEdit}
								<button class="btn-ghost hidden p-2 sm:inline-flex" title="Edit" aria-label="Edit {p.id}" onclick={() => startEdit(p)}><Pencil size={15} /></button>
								<button class="btn-ghost hidden p-2 sm:inline-flex" title="Remove" aria-label="Remove {p.id}" onclick={() => remove(p)}><Trash2 size={15} /></button>
							{/if}
							<button class="btn-primary shrink-0" disabled={loading !== null} onclick={() => loadPearl(p.id)}>
								{loading === p.id ? 'Loading…' : 'Load'}
							</button>
						</div>
						{#if canEdit}
							<!-- on phones the edit buttons sit under the row, the header row has no room -->
							<div class="mt-2 flex gap-1 pl-11 sm:hidden">
								<button class="btn-ghost px-2 py-1 text-xs" onclick={() => startEdit(p)}><Pencil size={13} /> Edit</button>
								<button class="btn-ghost px-2 py-1 text-xs" onclick={() => remove(p)}><Trash2 size={13} /> Remove</button>
							</div>
						{/if}
						{#if app.devMode && webhookFor === p.id && webhookUrl}
							<div class="mt-3 space-y-2 rounded-lg border border-zinc-800 bg-zinc-950/60 p-3">
								<div class="flex items-center gap-2 text-xs font-medium text-zinc-300"><Link2 size={13} /> Your webhook URL for {p.id}</div>
								<CopyField value={webhookUrl} size="sm" label="Copy URL" />
								<p class="text-xs text-zinc-500">
									Send a POST request to load the pearl, e.g. <span class="font-mono text-zinc-400">curl -X POST &lt;url&gt;</span>. Opening it in a
									browser shows a button. Anyone with the URL can load this pearl, so keep it secret. It's yours: it stops working if you lose
									access.
								</p>
								<div class="flex gap-2">
									<button class="btn-secondary px-2.5 py-1.5 text-xs" onclick={() => showWebhook(p.id, true)}><RefreshCw size={13} /> New URL</button>
									<button class="btn-danger px-2.5 py-1.5 text-xs" onclick={() => revokeWebhook(p.id)}>Turn off</button>
								</div>
							</div>
						{/if}
					</li>
				{/each}
			</ul>
		{/if}
	</section>

	{#if canEdit && settings}
		<section class="card divide-y divide-zinc-800">
			<div class="flex items-center gap-4 px-4 py-3">
				<div class="flex-1">
					<div class="text-sm text-zinc-200">Walk back afterwards</div>
					<div class="text-xs text-zinc-500">Return to where the bot stood before loading a pearl.</div>
				</div>
				<Switch label="Walk back afterwards" checked={!!settings.values[RETURN]} onchange={(v) => setOption(RETURN, v)} />
			</div>
			<div class="flex items-center gap-4 px-4 py-3">
				<div class="flex-1">
					<div class="text-sm text-zinc-200">Click twice</div>
					<div class="text-xs text-zinc-500">Flip the trapdoor back right away, for chambers that need it open and closed.</div>
				</div>
				<Switch label="Click twice" checked={!!settings.values[DOUBLE]} onchange={(v) => setOption(DOUBLE, v)} />
			</div>
		</section>
	{/if}

	{#if !app.devMode}
		<p class="text-center text-xs text-zinc-500">Turn on Developer mode in your user menu to get a webhook URL for each pearl.</p>
	{/if}
</div>
