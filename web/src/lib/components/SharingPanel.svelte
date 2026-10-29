<script lang="ts">
	import { capabilityLabels, roleOf, roles } from '#lib/access.js';
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { toasts } from '#lib/toast.svelte.js';
	import type { AccessView, Capability, Grant, Role } from '#lib/types.js';
	import { ChevronDown, Crown, Gamepad2, LoaderCircle, Trash2, UserRound } from '@lucide/svelte';

	let { instanceId }: { instanceId: string } = $props();

	let view = $state<AccessView | null>(null);
	let loadError = $state('');

	async function load() {
		try {
			view = await api.get<AccessView>(`/instances/${instanceId}/access`);
			loadError = '';
		} catch (e) {
			loadError = errorMessage(e);
		}
	}
	load();

	const mine = $derived(new Set(view?.myCapabilities ?? []));
	const grantableRoles = $derived(roles.filter((r) => r.capabilities.every((c) => mine.has(c))));

	// --- adding ---

	type Kind = 'user' | 'mc' | 'discord';
	let kind = $state<Kind>('user');
	let who = $state('');
	let role = $state<Role>('PLAYER');
	let adding = $state(false);
	let addError = $state('');

	const availableUsers = $derived(
		(view?.users ?? []).filter((u) => !view?.grants.some((g) => g.subject === `user:${u.id}`))
	);

	$effect(() => {
		// pick the first person when switching to users
		if (kind === 'user' && !availableUsers.some((u) => u.id === who)) who = availableUsers[0]?.id ?? '';
	});

	function switchKind(k: Kind) {
		kind = k;
		who = '';
		addError = '';
	}

	async function add(e: Event) {
		e.preventDefault();
		const value = who.trim();
		if (!value) return;
		adding = true;
		addError = '';
		try {
			view = await api.put<AccessView>(`/instances/${instanceId}/access`, { subject: `${kind}:${value}`, role, extra: [] });
			who = '';
			toasts.success('Shared');
		} catch (e) {
			addError = errorMessage(e);
		} finally {
			adding = false;
		}
	}

	// --- editing ---

	let expanded = $state<string | null>(null);

	async function update(grant: Grant, newRole: Role, extra: Capability[]) {
		try {
			view = await api.put<AccessView>(`/instances/${instanceId}/access`, { subject: grant.subject, role: newRole, extra });
		} catch (e) {
			toasts.error(e);
			await load();
		}
	}

	function toggleExtra(grant: Grant, cap: Capability, on: boolean) {
		const extra = on ? [...grant.extra, cap] : grant.extra.filter((c) => c !== cap);
		update(grant, grant.role ?? 'VIEWER', extra);
	}

	async function remove(grant: Grant) {
		if (!confirm(`Remove ${grant.label}'s access?`)) return;
		try {
			view = await api.del<AccessView>(`/instances/${instanceId}/access`, { subject: grant.subject });
			toasts.success('Access removed');
		} catch (e) {
			toasts.error(e);
		}
	}

	function kindOf(subject: string): Kind {
		return subject.split(':')[0] as Kind;
	}

	const kindLabel: Record<Kind, string> = { user: 'User', mc: 'Minecraft player', discord: 'Discord user' };
</script>

{#snippet discordIcon(size: number)}
	<svg viewBox="0 0 24 24" width={size} height={size} fill="currentColor" aria-hidden="true">
		<path
			d="M20.32 4.37A19.8 19.8 0 0 0 15.4 2.85a13.9 13.9 0 0 0-.63 1.29 18.4 18.4 0 0 0-5.53 0 13 13 0 0 0-.64-1.29 19.7 19.7 0 0 0-4.93 1.52C.53 9.05-.32 13.6.1 18.1a19.9 19.9 0 0 0 6.04 3.05c.49-.66.92-1.36 1.3-2.1a12.9 12.9 0 0 1-2.04-.98l.5-.39a14.2 14.2 0 0 0 12.2 0l.5.39c-.65.39-1.33.71-2.05.98.38.74.81 1.44 1.3 2.1a19.8 19.8 0 0 0 6.05-3.05c.5-5.2-.84-9.72-3.58-13.73ZM8.02 15.33c-1.18 0-2.16-1.08-2.16-2.42 0-1.33.95-2.42 2.16-2.42 1.2 0 2.18 1.1 2.16 2.42 0 1.34-.96 2.42-2.16 2.42Zm7.97 0c-1.18 0-2.15-1.08-2.15-2.42 0-1.33.95-2.42 2.15-2.42 1.21 0 2.18 1.1 2.16 2.42 0 1.34-.95 2.42-2.16 2.42Z"
		/>
	</svg>
{/snippet}

{#if loadError}
	<div class="card p-6 text-center text-sm text-zinc-400">
		{loadError}
		<div class="mt-3"><button class="btn-secondary" onclick={load}>Try again</button></div>
	</div>
{:else if !view}
	<div class="flex items-center justify-center gap-2 py-16 text-sm text-zinc-400"><LoaderCircle size={18} class="animate-spin" /> Loading…</div>
{:else}
	<div class="mx-auto max-w-3xl space-y-6">
		<form class="card space-y-4 p-4" onsubmit={add}>
			<div>
				<h2 class="font-medium">Share this instance</h2>
				<p class="mt-0.5 text-sm text-zinc-400">
					Access works the same on the web, in the in-game lobby and on Discord.
				</p>
			</div>
			<div class="inline-flex rounded-lg border border-zinc-800 bg-zinc-950 p-0.5 text-sm" role="tablist">
				{#each ['user', 'mc', 'discord'] as const as k}
					<button
						type="button"
						role="tab"
						aria-selected={kind === k}
						class="flex items-center gap-1.5 rounded-md px-3 py-1.5 {kind === k ? 'bg-zinc-800 text-zinc-100' : 'text-zinc-400 hover:text-zinc-200'}"
						onclick={() => switchKind(k)}
					>
						{#if k === 'user'}<UserRound size={14} />{:else if k === 'mc'}<Gamepad2 size={14} />{:else}{@render discordIcon(14)}{/if}
						<span class="hidden sm:inline">{kindLabel[k]}</span><span class="sm:hidden">{k === 'mc' ? 'Minecraft' : kindLabel[k].split(' ')[0]}</span>
					</button>
				{/each}
			</div>
			<div class="grid gap-3 sm:grid-cols-[1fr_12rem_auto] sm:items-end">
				<label class="block min-w-0">
					<span class="label">
						{kind === 'user' ? 'Person' : kind === 'mc' ? 'Minecraft name' : 'Discord user ID'}
					</span>
					{#if kind === 'user'}
						{#if availableUsers.length}
							<select class="input" bind:value={who}>
								{#each availableUsers as u (u.id)}<option value={u.id}>{u.username}</option>{/each}
							</select>
						{:else}
							<div class="input text-zinc-500">
								{view.users.length ? 'Everyone already has access' : 'No other users yet'}
							</div>
						{/if}
					{:else}
						<input
							class="input"
							bind:value={who}
							placeholder={kind === 'mc' ? 'Notch' : '123456789012345678'}
							inputmode={kind === 'discord' ? 'numeric' : undefined}
							maxlength={kind === 'mc' ? 16 : 21}
							autocomplete="off"
							autocapitalize="off"
							spellcheck="false"
						/>
					{/if}
				</label>
				<label class="block">
					<span class="label">Role</span>
					<select class="input" bind:value={role}>
						{#each grantableRoles as r (r.id)}<option value={r.id}>{r.label}</option>{/each}
					</select>
				</label>
				<button class="btn-primary" disabled={adding || !who.trim()}>Share</button>
			</div>
			<p class="text-xs text-zinc-500">
				{roleOf(role).label}: {roleOf(role).description.toLowerCase()}.
				{#if kind === 'mc'}Anyone logged in to Minecraft as this name gets access in the lobby, no web account needed.{/if}
				{#if kind === 'discord'}Gives access in this instance’s Discord channel. In Discord, enable developer mode and use “Copy User ID”.{/if}
				{#if kind === 'user' && app.isAdmin && !view.users.length}<a href="/users" class="text-emerald-400 underline">Add users</a> first.{/if}
			</p>
			{#if addError}<p class="text-sm text-red-400">{addError}</p>{/if}
		</form>

		<section>
			<h2 class="mb-2 text-sm font-semibold tracking-wide text-zinc-400 uppercase">Who has access</h2>
			<div class="card divide-y divide-zinc-800">
				{#if view.owner}
					<div class="flex items-center gap-3 p-4">
						<span class="flex size-9 shrink-0 items-center justify-center rounded-full bg-emerald-950 text-emerald-300"><Crown size={16} /></span>
						<div class="min-w-0 flex-1">
							<div class="font-medium">{view.owner}{view.owner === app.user?.username ? ' (you)' : ''}</div>
							<div class="text-xs text-zinc-500">Owner: full access, including their linked Minecraft and Discord accounts</div>
						</div>
					</div>
				{/if}
				{#if view.owner !== app.user?.username || !app.isAdmin}
					<div class="flex items-center gap-3 p-4">
						<span class="flex size-9 shrink-0 items-center justify-center rounded-full bg-zinc-800 text-zinc-300"><UserRound size={16} /></span>
						<div class="min-w-0 flex-1">
							<div class="font-medium">Administrator</div>
							<div class="text-xs text-zinc-500">Always has full access</div>
						</div>
					</div>
				{/if}
				{#each view.grants as grant (grant.subject)}
					{@const k = kindOf(grant.subject)}
					{@const r = roleOf(grant.role)}
					{@const open = expanded === grant.subject}
					<div class="p-4">
						<div class="flex flex-wrap items-center gap-3">
							{#if k === 'mc'}
								<img
									src="https://mc-heads.net/avatar/{grant.label}/36"
									alt=""
									class="size-9 shrink-0 rounded-md bg-zinc-800 [image-rendering:pixelated]"
								/>
							{:else if k === 'discord'}
								<span class="flex size-9 shrink-0 items-center justify-center rounded-full bg-indigo-950 text-indigo-300">{@render discordIcon(16)}</span>
							{:else}
								<span class="flex size-9 shrink-0 items-center justify-center rounded-full bg-zinc-800 text-sm font-semibold uppercase">
									{grant.label.charAt(0)}
								</span>
							{/if}
							<div class="min-w-0 flex-1">
								<div class="truncate font-medium">{grant.label}</div>
								<div class="text-xs text-zinc-500">
									{kindLabel[k]}{grant.extra.length ? ` · ${r.label} + ${grant.extra.length} more` : ''}
								</div>
							</div>
							<div class="flex items-center gap-1">
								<select
									class="input w-36 py-1.5"
									value={grant.role}
									disabled={!grant.editable}
									aria-label="Role for {grant.label}"
									onchange={(e) => update(grant, (e.target as HTMLSelectElement).value as Role, grant.extra)}
								>
									{#each roles as option (option.id)}
										<option value={option.id} disabled={!grantableRoles.includes(option)}>{option.label}</option>
									{/each}
								</select>
								<button
									class="btn-ghost p-2"
									aria-expanded={open}
									title="Fine-tune"
									aria-label="Fine-tune access for {grant.label}"
									onclick={() => (expanded = open ? null : grant.subject)}
								>
									<ChevronDown size={16} class="transition-transform {open ? 'rotate-180' : ''}" />
								</button>
								<button
									class="btn-ghost p-2 hover:text-red-400"
									disabled={!grant.editable}
									title="Remove access"
									aria-label="Remove access for {grant.label}"
									onclick={() => remove(grant)}
								>
									<Trash2 size={16} />
								</button>
							</div>
						</div>
						{#if open}
							<div class="mt-3 grid gap-1 rounded-lg border border-zinc-800 bg-zinc-950/50 p-3 sm:grid-cols-2">
								{#each Object.entries(capabilityLabels) as [cap, text]}
									{@const c = cap as Capability}
									{@const fromRole = r.capabilities.includes(c)}
									<label class="flex items-center gap-2 py-1 text-sm {fromRole ? 'text-zinc-500' : ''}">
										<input
											type="checkbox"
											class="size-4 accent-emerald-500"
											checked={grant.capabilities.includes(c)}
											disabled={fromRole || !grant.editable || !mine.has(c)}
											onchange={(e) => toggleExtra(grant, c, (e.target as HTMLInputElement).checked)}
										/>
										{text}
										{#if fromRole}<span class="text-xs">(role)</span>{/if}
									</label>
								{/each}
							</div>
						{/if}
					</div>
				{/each}
			</div>
			{#if !view.grants.length}
				<p class="mt-3 text-center text-sm text-zinc-500">Not shared with anyone yet.</p>
			{/if}
		</section>
	</div>
{/if}
