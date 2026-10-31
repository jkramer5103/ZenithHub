<script lang="ts">
	import { app } from '#lib/app.svelte.js';
	import AddAccountModal from '#lib/components/AddAccountModal.svelte';
	import InstanceCard from '#lib/components/InstanceCard.svelte';
	import NewInstanceModal from '#lib/components/NewInstanceModal.svelte';
	import { Check, Plus } from '@lucide/svelte';

	let showNewInstance = $state(false);
	let showAddAccount = $state(false);

	const overview = $derived(app.overview!);
	const instances = $derived(overview.instances);
	const hasAccounts = $derived(overview.accounts.some((a) => a.mine));

	// yours first, then what friends shared; the admin also sees everyone else's
	const groups = $derived(
		[
			{ id: 'mine', title: 'Your accounts', items: instances.filter((i) => i.mine) },
			{ id: 'shared', title: 'Shared with you', items: instances.filter((i) => !i.mine && (i.sharedWithMe || !app.isAdmin)) },
			{ id: 'others', title: 'Other users’ accounts', items: instances.filter((i) => !i.mine && !i.sharedWithMe && app.isAdmin) }
		].filter((g) => g.items.length)
	);

	const counts = $derived({
		online: instances.filter((i) => i.runtime.status?.online && i.runtime.phase === 'RUNNING').length,
		queue: instances.filter((i) => i.runtime.status?.inQueue && i.runtime.phase === 'RUNNING').length,
		crashed: instances.filter((i) => i.runtime.phase === 'BACKOFF').length
	});
</script>

<svelte:head><title>Instances · ZenithHub</title></svelte:head>

<div class="mb-6 flex flex-wrap items-center justify-between gap-3">
	<div>
		<h1 class="text-2xl font-semibold">Instances</h1>
		{#if instances.length}
			<p class="mt-1 text-sm text-zinc-400">
				{counts.online} of {instances.length} online{counts.queue ? ` · ${counts.queue} in queue` : ''}{counts.crashed
					? ` · ${counts.crashed} crashed`
					: ''}
			</p>
		{/if}
	</div>
	{#if instances.length}
		<button class="btn-primary" onclick={() => (showNewInstance = true)}><Plus size={16} /> New instance</button>
	{/if}
</div>

{#if instances.length}
	<div class="space-y-8">
		{#each groups as group (group.id)}
			<section>
				{#if groups.length > 1 || group.id !== 'mine'}
					<h2 class="mb-3 text-sm font-semibold tracking-wide text-zinc-400 uppercase">{group.title}</h2>
				{/if}
				<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
					{#each group.items as instance (instance.id)}
						<InstanceCard {instance} />
					{/each}
				</div>
			</section>
		{/each}
	</div>
{:else}
	<div class="card mx-auto w-full max-w-lg p-6">
		<h2 class="text-lg font-semibold">Get started</h2>
		<p class="mt-1 text-sm text-zinc-400">
			Two steps until your first account is online. Friends can also share their accounts with you; those show up here too.
		</p>
		<ol class="mt-5 space-y-3">
			<li class="flex items-center gap-3">
				<span
					class="flex size-7 shrink-0 items-center justify-center rounded-full text-sm font-semibold {hasAccounts
						? 'bg-emerald-600 text-white'
						: 'bg-zinc-800 text-zinc-300'}"
				>
					{#if hasAccounts}<Check size={15} />{:else}1{/if}
				</span>
				<span class="flex-1 text-sm">Add a Minecraft account</span>
				{#if !hasAccounts}
					<button class="btn-primary" onclick={() => (showAddAccount = true)}>Add account</button>
				{/if}
			</li>
			<li class="flex items-center gap-3">
				<span class="flex size-7 shrink-0 items-center justify-center rounded-full bg-zinc-800 text-sm font-semibold text-zinc-300">2</span>
				<span class="flex-1 text-sm {hasAccounts ? '' : 'text-zinc-500'}">Pick a server and start an instance</span>
				{#if hasAccounts}
					<button class="btn-primary" onclick={() => (showNewInstance = true)}>New instance</button>
				{/if}
			</li>
		</ol>
	</div>
{/if}

{#if showNewInstance}<NewInstanceModal onclose={() => (showNewInstance = false)} />{/if}
{#if showAddAccount}<AddAccountModal onclose={() => (showAddAccount = false)} />{/if}
