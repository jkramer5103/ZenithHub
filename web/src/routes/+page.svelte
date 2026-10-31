<script lang="ts">
	import { api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import OwnerBadge from '#lib/components/OwnerBadge.svelte';
	import PhaseBadge from '#lib/components/PhaseBadge.svelte';
	import { instanceIssues, type Issue } from '#lib/issues.js';
	import type { AfkSummary, AfkZoneStatus, Instance } from '#lib/types.js';
	import { ChevronRight, CircleAlert, CircleCheck, LayoutGrid, TriangleAlert } from '@lucide/svelte';

	const overview = $derived(app.overview!);
	const instances = $derived(overview.instances);

	// what each one earned over the last week and ever; the counters themselves come live with the status
	let summary = $state<AfkSummary>({});
	async function loadSummary() {
		try {
			summary = await api.get<AfkSummary>('/afk/summary');
		} catch {
			// the dashboard still works without it
		}
	}
	$effect(() => {
		loadSummary();
		const timer = setInterval(loadSummary, 60_000);
		return () => clearInterval(timer);
	});

	interface Row {
		instance: Instance;
		server: string;
		afk?: AfkZoneStatus;
		issues: Issue[];
	}

	const rows = $derived(
		instances.map((instance): Row => {
			const status = instance.runtime.phase === 'RUNNING' ? instance.runtime.status : null;
			return {
				instance,
				server: overview.servers.find((s) => s.id === instance.serverId)?.name ?? 'unknown server',
				afk: status?.online ? status.afkZone : undefined,
				issues: instanceIssues(
					instance,
					overview.accounts.find((a) => a.id === instance.accountId)
				)
			};
		})
	);

	// yours first, then what friends shared; the admin also sees everyone else's
	const groups = $derived(
		[
			{ id: 'mine', title: 'Your accounts', items: rows.filter((r) => r.instance.mine) },
			{ id: 'shared', title: 'Shared with you', items: rows.filter((r) => !r.instance.mine && (r.instance.sharedWithMe || !app.isAdmin)) },
			{ id: 'others', title: 'Other users’ accounts', items: rows.filter((r) => !r.instance.mine && !r.instance.sharedWithMe && app.isAdmin) }
		].filter((g) => g.items.length)
	);

	const attention = $derived(rows.filter((r) => r.issues.length).sort((a, b) => level(a) - level(b)));
	function level(r: Row) {
		return r.issues.some((i) => i.level === 'error') ? 0 : 1;
	}

	const online = $derived(rows.filter((r) => r.instance.runtime.phase === 'RUNNING' && r.instance.runtime.status?.online).length);
	const earning = $derived(rows.filter((r) => r.afk?.state === 'IN_ZONE' && r.afk.earning === true).length);
	const afkRows = $derived(rows.filter((r) => r.afk || summary[r.instance.id]));
	// a total only makes sense when they all count the same thing
	const labels = $derived([...new Set(rows.map((r) => r.afk?.rewardLabel).filter(Boolean))]);
	const label = $derived(labels.length === 1 ? labels[0]! : 'Reward');
	// HugoSMP pays one a minute while an account earns, so that's what a day comes to
	const PER_DAY = 24 * 60;
	const perDay = $derived(earning * PER_DAY);
	const current = $derived(rows.reduce((sum, r) => sum + (r.afk?.reward ?? 0), 0));
	const lifetime = $derived(afkRows.reduce((sum, r) => sum + (summary[r.instance.id]?.lifetime ?? 0), 0));
	function earningNow(r: Row) {
		return r.afk?.state === 'IN_ZONE' && r.afk.earning === true;
	}

	function amount(n: number | null | undefined) {
		if (n == null) return '-';
		return n.toLocaleString(undefined, { maximumFractionDigits: n < 10 ? 1 : 0 });
	}

	function afkView(afk: AfkZoneStatus | undefined) {
		switch (afk?.state) {
			case 'IN_ZONE':
				if (afk.earning === true) return { text: 'Earning', cls: 'text-emerald-300' };
				if (afk.earning === false) return { text: 'Not earning', cls: 'text-red-300' };
				return { text: 'Checking…', cls: 'text-zinc-400' };
			case 'WAITING':
			case 'ENTERING':
				return { text: 'Going in…', cls: 'text-sky-300' };
			case 'OUTSIDE':
				return { text: 'Outside the zone', cls: 'text-zinc-400' };
			case 'PLAYING':
				return { text: 'Someone is playing', cls: 'text-zinc-400' };
			case 'FAILED':
				return { text: "Couldn't get in", cls: 'text-red-300' };
			default:
				return null;
		}
	}

	// the instance name only tells them apart when an account is on several servers
	function subtitle(r: Row) {
		const several = rows.filter((o) => o.instance.accountId === r.instance.accountId).length > 1;
		return several ? `${r.server} · ${r.instance.name}` : r.server;
	}

	function href(r: Row) {
		return `/instances/${r.instance.id}${r.afk || summary[r.instance.id] ? '?tab=afk' : ''}`;
	}
</script>

<svelte:head><title>Dashboard · ZenithHub</title></svelte:head>

<h1 class="mb-6 text-2xl font-semibold">Dashboard</h1>

{#if !rows.length}
	<div class="card mx-auto w-full max-w-lg p-6 text-center">
		<h2 class="text-lg font-semibold">Nothing here yet</h2>
		<p class="mt-1 text-sm text-zinc-400">Your accounts show up here once they have an instance.</p>
		<a href="/instances" class="btn-primary mt-4"><LayoutGrid size={16} /> Set up an instance</a>
	</div>
{:else}
	<div class="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
		<div class="card px-4 py-3">
			<div class="text-2xl font-semibold tabular-nums">{online}<span class="text-base text-zinc-500">/{rows.length}</span></div>
			<div class="text-xs text-zinc-500">online</div>
		</div>
		{#if afkRows.length}
			<div class="card px-4 py-3">
				<div class="text-2xl font-semibold tabular-nums {earning ? 'text-emerald-300' : ''}">
					{earning}<span class="text-base text-zinc-500">/{afkRows.length}</span>
				</div>
				<div class="text-xs text-zinc-500">earning {label}</div>
			</div>
			<div class="card px-4 py-3">
				<div class="text-2xl font-semibold tabular-nums">{amount(current)}</div>
				<div class="text-xs text-zinc-500">{label} now</div>
			</div>
			<div class="card px-4 py-3">
				<div class="text-2xl font-semibold tabular-nums {perDay ? 'text-emerald-300' : ''}">+{amount(perDay)}</div>
				<div class="text-xs text-zinc-500">{label} a day at this rate</div>
			</div>
			<div class="card px-4 py-3">
				<div class="text-2xl font-semibold tabular-nums">{amount(lifetime)}</div>
				<div class="text-xs text-zinc-500">{label} earned in total</div>
			</div>
		{/if}
		<div class="card px-4 py-3">
			<div class="text-2xl font-semibold tabular-nums {attention.length ? 'text-amber-300' : 'text-zinc-100'}">{attention.length}</div>
			<div class="text-xs text-zinc-500">{attention.length === 1 ? 'needs' : 'need'} attention</div>
		</div>
	</div>

	{#if attention.length}
		<section class="mt-8">
			<h2 class="mb-3 text-sm font-semibold tracking-wide text-zinc-400 uppercase">Needs attention</h2>
			<ul class="card divide-y divide-zinc-800">
				{#each attention as r (r.instance.id)}
					<li>
						<a href={href(r)} class="flex items-start gap-3 px-4 py-3 hover:bg-zinc-900/60">
							<img
								src="https://mc-heads.net/avatar/{r.instance.accountName}/32"
								alt=""
								class="mt-0.5 size-8 shrink-0 rounded bg-zinc-800 [image-rendering:pixelated]"
								loading="lazy"
							/>
							<div class="min-w-0 flex-1">
								<div class="truncate text-sm font-medium">
									{r.instance.accountName} <span class="font-normal text-zinc-500">· {r.server}</span>
								</div>
								{#each r.issues as issue (issue.title)}
									<div class="mt-1 flex items-start gap-1.5 text-sm {issue.level === 'error' ? 'text-red-300' : 'text-amber-300'}">
										{#if issue.level === 'error'}<CircleAlert size={15} class="mt-0.5 shrink-0" />{:else}<TriangleAlert
												size={15}
												class="mt-0.5 shrink-0"
											/>{/if}
										<div class="min-w-0">
											<span class="font-medium">{issue.title}</span>
											{#if issue.detail}<span class="block text-xs break-words text-zinc-400">{issue.detail}</span>{/if}
										</div>
									</div>
								{/each}
							</div>
							<ChevronRight size={16} class="mt-2 shrink-0 text-zinc-600" />
						</a>
					</li>
				{/each}
			</ul>
		</section>
	{:else}
		<div class="mt-6 flex items-center gap-2 text-sm text-emerald-300"><CircleCheck size={16} /> Everything looks fine.</div>
	{/if}

	{#each groups as group (group.id)}
		<section class="mt-8">
			<h2 class="mb-3 text-sm font-semibold tracking-wide text-zinc-400 uppercase">{group.title}</h2>
			<ul class="card divide-y divide-zinc-800">
				{#each group.items as r (r.instance.id)}
					{@const afk = afkView(r.afk)}
					{@const earned = summary[r.instance.id]}
					<li>
						<a href={href(r)} class="flex flex-col gap-3 px-4 py-3 hover:bg-zinc-900/60 md:flex-row md:items-center md:gap-4">
							<div class="flex min-w-0 flex-1 items-center gap-3">
								<img
									src="https://mc-heads.net/avatar/{r.instance.accountName}/40"
									alt=""
									class="size-10 shrink-0 rounded-md bg-zinc-800 [image-rendering:pixelated]"
									loading="lazy"
								/>
								<div class="min-w-0 flex-1">
									<div class="flex items-center gap-2">
										<span class="truncate font-medium">{r.instance.accountName}</span>
										{#if r.issues.length}
											<CircleAlert
												size={15}
												class="hidden shrink-0 md:block {level(r) === 0 ? 'text-red-400' : 'text-amber-400'}"
												aria-label="Needs attention"
											/>
										{/if}
									</div>
									<div class="truncate text-xs text-zinc-500">{subtitle(r)}</div>
									{#if !r.instance.mine}<div class="mt-1"><OwnerBadge mine={false} ownerName={r.instance.ownerName} /></div>{/if}
								</div>
								<PhaseBadge runtime={r.instance.runtime} />
							</div>
							{#if r.afk || earned}
								<div class="grid grid-cols-4 gap-2 text-center md:w-[32rem] md:shrink-0 md:grid-cols-5">
									<!-- phones: a line of its own above the numbers -->
									<div class="col-span-4 min-w-0 text-left md:col-span-1 md:text-center">
										<div class="truncate text-sm font-medium {afk?.cls ?? 'text-zinc-500'}">{afk?.text ?? 'Not in game'}</div>
										<div class="hidden text-[11px] text-zinc-500 md:block">AFK zone</div>
									</div>
									<div>
										<div class="text-sm font-semibold tabular-nums">{r.afk?.rewardText ?? amount(r.afk?.reward)}</div>
										<div class="truncate text-[11px] text-zinc-500">{r.afk?.rewardLabel || label} now</div>
									</div>
									<div>
										<div class="text-sm font-semibold tabular-nums {earningNow(r) ? 'text-emerald-300' : ''}">
											{r.afk ? `+${amount(earningNow(r) ? PER_DAY : 0)}` : '-'}
										</div>
										<div class="text-[11px] text-zinc-500">a day</div>
									</div>
									<div>
										<div class="text-sm font-semibold tabular-nums">{earned ? `+${amount(earned.week)}` : '-'}</div>
										<div class="text-[11px] text-zinc-500">7 days</div>
									</div>
									<div>
										<div class="text-sm font-semibold tabular-nums">{earned ? amount(earned.lifetime) : '-'}</div>
										<div class="text-[11px] text-zinc-500">in total</div>
									</div>
								</div>
							{/if}
						</a>
					</li>
				{/each}
			</ul>
		</section>
	{/each}
{/if}
