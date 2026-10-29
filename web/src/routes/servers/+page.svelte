<script lang="ts">
	import { api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import OwnerBadge from '#lib/components/OwnerBadge.svelte';
	import ServerModal from '#lib/components/ServerModal.svelte';
	import { attempt } from '#lib/toast.svelte.js';
	import type { Server } from '#lib/types.js';
	import { Pencil, Plus, SlidersHorizontal, Trash2 } from '@lucide/svelte';

	let editing = $state<Server | null | undefined>(undefined);

	const servers = $derived(app.overview?.servers ?? []);

	function instanceCount(server: Server) {
		return app.overview?.instances.filter((i) => i.serverId === server.id).length ?? 0;
	}

	async function remove(server: Server) {
		if (!confirm(`Remove ${server.name}?`)) return;
		await attempt(async () => {
			await api.del(`/servers/${server.id}`);
			await app.refresh();
		}, 'Server removed');
	}
</script>

<svelte:head><title>Servers · ZenithProxy</title></svelte:head>

<div class="mb-6 flex flex-wrap items-center justify-between gap-3">
	<div>
		<h1 class="text-2xl font-semibold">Servers</h1>
		<p class="mt-1 text-sm text-zinc-400">
			Servers your accounts can play on. Shared servers are set up by the admin for everyone; servers you add are only yours.
		</p>
	</div>
	<button class="btn-primary" onclick={() => (editing = null)}><Plus size={16} /> Add server</button>
</div>

{#if servers.length === 0}
	<div class="card py-12 text-center text-sm text-zinc-400">No servers yet.</div>
{:else}
	<div class="card divide-y divide-zinc-800">
		{#each servers as server (server.id)}
			{@const count = instanceCount(server)}
			<div class="flex items-center gap-3 p-4">
				<div class="min-w-0 flex-1">
					<div class="flex items-center gap-2">
						<span class="truncate font-medium">{server.name}</span>
						{#if server.shared}
							<span class="rounded bg-zinc-800 px-1.5 py-0.5 text-[11px] text-zinc-400" title="Set up by the admin, usable by everyone">Shared</span>
						{:else}
							<OwnerBadge mine={server.mine} ownerName={server.ownerName} />
						{/if}
					</div>
					<div class="truncate font-mono text-xs text-zinc-500">
						{server.address}{server.port !== 25565 ? `:${server.port}` : ''} · {count} instance{count === 1 ? '' : 's'}
					</div>
				</div>
				{#if server.editable}
					<a class="btn-ghost p-2" title="Settings for instances on this server" href="/servers/{server.id}"><SlidersHorizontal size={16} /></a>
					<button class="btn-ghost p-2" title="Edit" onclick={() => (editing = server)}><Pencil size={16} /></button>
					<button
						class="btn-ghost p-2 hover:text-red-400"
						title={count ? 'Delete its instances first' : 'Remove server'}
						disabled={count > 0}
						onclick={() => remove(server)}
					>
						<Trash2 size={16} />
					</button>
				{/if}
			</div>
		{/each}
	</div>
{/if}

{#if editing !== undefined}<ServerModal server={editing} onclose={() => (editing = undefined)} />{/if}
