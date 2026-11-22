<script lang="ts">
	import { api, ApiError } from '#lib/api.js';
	import { relative } from '#lib/format.js';
	import Switch from '#lib/components/settings/Switch.svelte';
	import PluginInstallModal from '#lib/components/plugins/PluginInstallModal.svelte';
	import PluginSettingsModal from '#lib/components/plugins/PluginSettingsModal.svelte';
	import PluginRemoveModal from '#lib/components/plugins/PluginRemoveModal.svelte';
	import { plugins as host } from '#lib/plugins.svelte.js';
	import { attempt, toasts } from '#lib/toast.svelte.js';
	import type { PluginList, PluginView, StagedPlugin } from '#lib/types.js';
	import { AlertTriangle, ExternalLink, LoaderCircle, Puzzle, RotateCw, Settings2, ShieldAlert, Trash2, Upload } from '@lucide/svelte';
	import { onMount } from 'svelte';
	import { app } from '#lib/app.svelte.js';

	let data = $state<PluginList | null>(null);
	let staged = $state<StagedPlugin | null>(null);
	let uploading = $state(false);
	let restarting = $state(false);
	let settingsFor = $state<PluginView | null>(null);
	let removing = $state<PluginView | null>(null);
	let fileInput: HTMLInputElement;

	async function load() {
		data = await api.get<PluginList>('/plugins');
	}
	onMount(() => {
		void attempt(load);
		// installs, toggles and settings changes elsewhere (another tab, another admin)
		return app.onMessage((msg) => {
			if (msg.type === 'changed' && !restarting) void load().catch(() => {});
		});
	});

	async function upload(file: File) {
		uploading = true;
		try {
			const res = await fetch('/api/plugins/upload', {
				method: 'POST',
				headers: { 'Content-Type': 'application/zip' },
				body: file,
				credentials: 'same-origin'
			});
			const text = await res.text();
			let json: unknown = null;
			try {
				json = text ? JSON.parse(text) : null;
			} catch {
				// a reverse proxy's error page
			}
			if (!res.ok) throw new ApiError(res.status, (json as { error?: string } | null)?.error ?? `Upload failed (${res.status})`);
			staged = json as StagedPlugin;
		} catch (e) {
			toasts.error(e);
		} finally {
			uploading = false;
			fileInput.value = '';
		}
	}

	async function toggle(p: PluginView, on: boolean) {
		await attempt(async () => {
			await api.patch(`/plugins/${p.id}`, { enabled: on });
			await load();
		}, on ? `${p.name} is on` : `${p.name} is off`);
	}

	async function restartHub() {
		if (!confirm('Restart ZenithHub now? The web UI and the lobby are gone for a few seconds. Instances keep running and reconnect.')) return;
		restarting = true;
		try {
			await api.post('/plugins/restart');
		} catch (e) {
			restarting = false;
			toasts.error(e);
			return;
		}
		// wait for the old process to go and the new one to answer
		await new Promise((r) => setTimeout(r, 2500));
		for (let i = 0; i < 120; i++) {
			try {
				const res = await fetch('/api/session', { credentials: 'same-origin' });
				if (res.ok) {
					location.reload();
					return;
				}
			} catch {
				// not up yet
			}
			await new Promise((r) => setTimeout(r, 1000));
		}
		restarting = false;
		toasts.show('error', 'ZenithHub did not come back. Check the container logs.');
	}

	async function restartInstances() {
		await attempt(async () => {
			const r = await api.post<{ restarted: number }>('/plugins/restart-instances');
			toasts.success(r.restarted === 1 ? 'Restarting 1 instance' : `Restarting ${r.restarted} instances`);
			setTimeout(() => void load().catch(() => {}), 4000);
		});
	}

	const mixins = (n: number) => (n === 1 ? '1 mixin' : `${n} mixins`);

	function parts(p: PluginView) {
		const list: string[] = [];
		if (p.parts?.manager.present) list.push(p.parts.manager.mixins ? `Manager · ${mixins(p.parts.manager.mixins)}` : 'Manager');
		if (p.parts?.bot.present) list.push(p.parts.bot.mixins ? `Bots · ${mixins(p.parts.bot.mixins)}` : 'Bots');
		if (p.parts?.web.present) list.push('Web UI');
		return list;
	}
</script>

<svelte:head><title>Plugins · ZenithHub</title></svelte:head>

<input
	bind:this={fileInput}
	type="file"
	accept=".zip,application/zip"
	class="hidden"
	onchange={(e) => {
		const file = e.currentTarget.files?.[0];
		if (file) void upload(file);
	}}
/>

<div class="mb-6 flex flex-wrap items-center justify-between gap-3">
	<div>
		<h1 class="text-2xl font-semibold">Plugins</h1>
		<p class="mt-1 max-w-2xl text-sm text-zinc-400">
			Plugins add features to ZenithHub, its bots and this web UI, or change existing ones. A plugin is a zip file. Plugins run with full access to
			everything, so only install ones you trust.
		</p>
	</div>
	<button class="btn-primary" disabled={uploading} onclick={() => fileInput.click()}>
		{#if uploading}<LoaderCircle size={16} class="animate-spin" /> Reading…{:else}<Upload size={16} /> Install plugin{/if}
	</button>
</div>

{#if restarting}
	<div class="card mb-4 flex items-center gap-3 p-4 text-sm" role="status">
		<LoaderCircle size={18} class="animate-spin text-emerald-400" /> Restarting ZenithHub… The page reloads when it's back.
	</div>
{/if}

{#if data}
	{#if data.safeMode}
		<div class="mb-4 flex gap-3 rounded-xl border border-amber-800/70 bg-amber-950/50 p-4 text-sm text-amber-100" role="alert">
			<ShieldAlert size={18} class="mt-0.5 shrink-0 text-amber-400" />
			<div>
				<div class="font-medium">Safe mode: plugins for the manager aren't loaded</div>
				<div class="mt-0.5 text-amber-200/80">{data.safeMode}</div>
				<div class="mt-0.5 text-amber-200/80">Turn off or remove the plugin that broke the start, then restart ZenithHub.</div>
				{#if data.canRestart}<button class="btn-secondary mt-3" onclick={restartHub}><RotateCw size={15} /> Restart ZenithHub</button>{/if}
			</div>
		</div>
	{:else if data.needsRestart}
		<div class="mb-4 flex flex-wrap items-center gap-3 rounded-xl border border-sky-900/70 bg-sky-950/40 p-4 text-sm text-sky-100">
			<RotateCw size={18} class="shrink-0 text-sky-400" />
			<div class="min-w-0 flex-1">
				<div class="font-medium">Restart ZenithHub to finish</div>
				<div class="text-sky-200/80">
					{data.canRestart
						? 'Changes to the manager part of a plugin load when ZenithHub starts. Instances keep running.'
						: 'Changes to the manager part of a plugin load when ZenithHub starts. Restart the container to apply them.'}
				</div>
			</div>
			{#if data.canRestart}<button class="btn-primary" disabled={restarting} onclick={restartHub}><RotateCw size={15} /> Restart now</button>{/if}
		</div>
	{/if}

	{#if data.staleInstances.length}
		<div class="mb-4 flex flex-wrap items-center gap-3 rounded-xl border border-zinc-800 bg-zinc-900 p-4 text-sm">
			<RotateCw size={18} class="shrink-0 text-zinc-400" />
			<div class="min-w-0 flex-1">
				<div class="font-medium">
					{data.staleInstances.length === 1 ? '1 running instance uses' : `${data.staleInstances.length} running instances use`} older plugins
				</div>
				<div class="truncate text-zinc-400">{data.staleInstances.map((i) => i.name).join(', ')}: plugin changes for bots apply when they restart.</div>
			</div>
			<button class="btn-secondary" onclick={restartInstances}>Restart them</button>
		</div>
	{/if}

	{#each data.errors as error (error)}
		<div class="mb-4 flex gap-3 rounded-xl border border-red-900/60 bg-red-950/40 p-4 text-sm text-red-200" role="alert">
			<AlertTriangle size={18} class="mt-0.5 shrink-0 text-red-400" />
			<div class="min-w-0 break-words">{error}</div>
		</div>
	{/each}
{/if}

{#if data === null}
	<div class="card py-12 text-center text-sm text-zinc-500">Loading…</div>
{:else if data.plugins.length === 0}
	<div class="card px-6 py-12 text-center">
		<span class="mx-auto flex size-11 items-center justify-center rounded-xl bg-zinc-800 text-zinc-300"><Puzzle size={22} /></span>
		<h2 class="mt-3 font-semibold">No plugins yet</h2>
		<p class="mx-auto mt-1 max-w-sm text-sm text-zinc-400">Install a plugin zip to add pages, bot features, settings or anything else.</p>
	</div>
{:else}
	<div class="flex flex-col gap-3">
		{#each data.plugins as p (p.id)}
			{@const webError = host.errors[p.id]}
			<div class="card p-4">
				<div class="flex items-start gap-3">
					<span class="flex size-10 shrink-0 items-center justify-center rounded-lg bg-zinc-800 {p.enabled ? 'text-emerald-300' : 'text-zinc-500'}">
						<Puzzle size={19} />
					</span>
					<div class="min-w-0 flex-1">
						<div class="flex flex-wrap items-baseline gap-x-2">
							<span class="font-medium">{p.name ?? p.id}</span>
							{#if p.version}<span class="text-xs text-zinc-500">v{p.version}</span>{/if}
							{#if p.needsRestart}<span class="rounded bg-sky-950 px-1.5 py-0.5 text-[11px] text-sky-300">restart pending</span>{/if}
						</div>
						{#if p.authors?.length}<div class="text-xs text-zinc-500">by {p.authors.join(', ')}</div>{/if}
						{#if p.description}<p class="mt-1 text-sm text-zinc-300">{p.description}</p>{/if}
						<div class="mt-2 flex flex-wrap gap-1.5">
							{#each parts(p) as part (part)}
								<span class="rounded-md border border-zinc-800 bg-zinc-950 px-2 py-0.5 text-[11px] text-zinc-400">{part}</span>
							{/each}
						</div>
						<div class="mt-2 text-xs text-zinc-600">
							Installed {relative(p.installedAt)}{p.updatedAt !== p.installedAt ? ` · updated ${relative(p.updatedAt)}` : ''}
							{#if p.url}
								· <a href={p.url} target="_blank" rel="noreferrer" class="inline-flex items-center gap-0.5 hover:text-zinc-300"
									>Website <ExternalLink size={11} /></a
								>
							{/if}
						</div>
					</div>
					<Switch checked={p.enabled} label="{p.name ?? p.id} on" onchange={(on) => toggle(p, on)} />
				</div>

				{#if p.errors.length || webError}
					<ul class="mt-3 flex flex-col gap-1 rounded-lg border border-red-900/60 bg-red-950/30 p-3 text-xs text-red-200">
						{#each p.errors as error (error)}<li class="break-words">{error}</li>{/each}
						{#if webError}<li class="break-words">In this browser: {webError}</li>{/if}
					</ul>
				{/if}

				<div class="mt-3 flex flex-wrap gap-2 border-t border-zinc-800 pt-3">
					{#if p.hasSettings}
						<button class="btn-secondary" onclick={() => (settingsFor = p)}><Settings2 size={15} /> Settings</button>
					{/if}
					<button class="btn-secondary" disabled={uploading} onclick={() => fileInput.click()}><Upload size={15} /> Update</button>
					<button class="btn-ghost ml-auto hover:text-red-400" onclick={() => (removing = p)}><Trash2 size={15} /> Remove</button>
				</div>
			</div>
		{/each}
	</div>
	{#if !data.jvm}
		<p class="mt-4 text-xs text-zinc-500">Bot parts need ZenithProxy.jar and a JVM command (ZENITH_JVM_COMMAND).</p>
	{/if}
{/if}

{#if staged}
	<PluginInstallModal
		{staged}
		onclose={() => {
			const token = staged?.token;
			staged = null;
			if (token) void api.del(`/plugins/staged/${token}`).catch(() => {});
		}}
		oninstalled={() => {
			staged = null;
			void attempt(load);
			void app.refresh().catch(() => {});
		}}
	/>
{/if}
{#if settingsFor}
	<PluginSettingsModal plugin={settingsFor} onclose={() => (settingsFor = null)} />
{/if}
{#if removing}
	<PluginRemoveModal
		plugin={removing}
		onclose={() => (removing = null)}
		onremoved={() => {
			removing = null;
			void attempt(load);
		}}
	/>
{/if}
