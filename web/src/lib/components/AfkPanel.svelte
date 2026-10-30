<script lang="ts">
	import { api } from '#lib/api.js';
	import { runAction } from '#lib/actions.js';
	import { DetailsPoller } from '#lib/details.svelte.js';
	import { duration } from '#lib/format.js';
	import Switch from '#lib/components/settings/Switch.svelte';
	import type { AfkHistory, AfkZoneStatus, Capability } from '#lib/types.js';
	import { CircleAlert, CircleCheck, Hourglass, LogIn, LogOut, MapPinned, Settings2 } from '@lucide/svelte';

	let { instanceId, capabilities, running }: { instanceId: string; capabilities: Capability[]; running: boolean } = $props();

	const canKeep = $derived(capabilities.includes('CONTROL'));
	const canMove = $derived(capabilities.includes('PLAY'));
	const canSetup = $derived(capabilities.includes('SETTINGS'));

	const poller = new DetailsPoller(instanceId, 3000);
	$effect(() => poller.start());
	const afk = $derived<AfkZoneStatus | undefined>(poller.details?.afkZone);
	const screen = $derived(poller.details?.screen);
	// servers pad the sidebar with blank lines at the ends
	const sidebarLines = $derived.by(() => {
		const lines = screen?.sidebar?.lines ?? [];
		let start = 0;
		let end = lines.length;
		while (start < end && !lines[start].trim()) start++;
		while (end > start && !lines[end - 1].trim()) end--;
		return lines.slice(start, end);
	});

	let history = $state<AfkHistory | null>(null);
	async function loadHistory() {
		try {
			history = await api.get<AfkHistory>(`/instances/${instanceId}/afk`);
		} catch {
			history ??= { samples: [], sessions: [] };
		}
	}
	$effect(() => {
		void loadHistory();
		const timer = setInterval(loadHistory, 60_000);
		return () => clearInterval(timer);
	});

	let now = $state(Date.now());
	$effect(() => {
		const timer = setInterval(() => (now = Date.now()), 1000);
		return () => clearInterval(timer);
	});

	let busy = $state(false);
	async function act(action: string, extra: Record<string, unknown> = {}) {
		busy = true;
		await runAction(instanceId, { action, ...extra });
		await poller.refresh();
		busy = false;
	}

	const label = $derived(afk?.rewardLabel || 'Reward');
	const earned = $derived(afk?.reward != null && afk.rewardAtEntry != null ? afk.reward - afk.rewardAtEntry : null);
	const perHour = $derived.by(() => {
		if (earned == null || !afk?.since) return null;
		const hours = (now - afk.since) / 3_600_000;
		return hours >= 0.1 ? earned / hours : null;
	});

	function amount(n: number | null | undefined) {
		if (n == null) return '-';
		return n.toLocaleString(undefined, { maximumFractionDigits: n < 10 ? 1 : 0 });
	}
	function when(ts: number) {
		const d = new Date(ts);
		const today = new Date().toDateString() === d.toDateString();
		const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
		return today ? time : `${d.toLocaleDateString([], { day: 'numeric', month: 'short' })} ${time}`;
	}

	/** what to say about the zone, by state */
	const hero = $derived.by(() => {
		if (!running || !afk || afk.state === 'OFFLINE')
			return { tone: 'zinc', title: 'Offline', text: 'Connect the account first. With "Keep in the zone" on, it goes back in after connecting.' };
		switch (afk.state) {
			case 'IN_ZONE':
				return { tone: 'emerald', title: 'In the AFK zone', text: `For ${duration((now - (afk.since ?? now)) / 1000)}` };
			case 'WAITING':
				return {
					tone: 'sky',
					title: 'Going in soon',
					text: afk.nextAttemptAt
						? `Sends /${afk.enterCommand} in ${duration(Math.max(0, (afk.nextAttemptAt - now) / 1000))}, a random wait so it looks like a person.`
						: 'Waiting'
				};
			case 'ENTERING':
				return {
					tone: 'sky',
					title: 'Going in',
					text: `Sent /${afk.enterCommand}${afk.attempts > 1 ? ` (try ${afk.attempts} of ${afk.maxAttempts})` : ''}, waiting for the server to confirm.`
				};
			case 'PLAYING':
				return { tone: 'zinc', title: 'Paused', text: 'Someone is playing on the account. It goes back in once they leave.' };
			case 'FAILED':
				return { tone: 'red', title: "Couldn't get in", text: afk.failure ?? 'The server never confirmed the zone.' };
			default:
				return {
					tone: 'zinc',
					title: 'Outside the zone',
					text: afk.leftReason ? `Left ${duration((now - (afk.leftAt ?? now)) / 1000)} ago: ${afk.leftReason}` : 'Not in the AFK zone.'
				};
		}
	});
	const toneClass: Record<string, string> = {
		emerald: 'border-emerald-900/60 bg-emerald-950/30 text-emerald-300',
		sky: 'border-sky-900/60 bg-sky-950/30 text-sky-300',
		red: 'border-red-900/60 bg-red-950/30 text-red-300',
		zinc: 'border-zinc-800 bg-zinc-900 text-zinc-300'
	};

	// the reward over the last two weeks, as a line
	const W = 600;
	const H = 120;
	const chart = $derived.by(() => {
		const s = history?.samples ?? [];
		const points = afk?.reward != null && s.length ? [...s, [now, afk.reward] as [number, number]] : s;
		if (points.length < 2) return null;
		const t0 = points[0][0];
		const t1 = points[points.length - 1][0];
		const values = points.map((p) => p[1]);
		const lo = Math.min(...values);
		const hi = Math.max(...values);
		const x = (t: number) => (t1 === t0 ? 0 : ((t - t0) / (t1 - t0)) * W);
		const y = (v: number) => (hi === lo ? H / 2 : H - 4 - ((v - lo) / (hi - lo)) * (H - 8));
		const line = points.map((p, i) => `${i ? 'L' : 'M'}${x(p[0]).toFixed(1)},${y(p[1]).toFixed(1)}`).join(' ');
		return { line, area: `${line} L${W},${H} L0,${H} Z`, lo, hi, from: t0, first: values[0], last: values[values.length - 1] };
	});
</script>

<div class="mx-auto w-full max-w-3xl space-y-4">
	<section class="card overflow-hidden">
		<div class="flex items-start gap-3 border-b px-4 py-4 {toneClass[hero.tone]}">
			<MapPinned size={20} class="mt-0.5 shrink-0" />
			<div class="min-w-0 flex-1">
				<h2 class="font-semibold">{hero.title}</h2>
				<p class="text-sm text-zinc-300">{hero.text}</p>
			</div>
		</div>

		{#if afk?.state === 'IN_ZONE'}
			{#if afk.earning === false}
				<div class="flex items-start gap-3 border-b border-zinc-800 bg-amber-950/30 px-4 py-3 text-amber-200">
					<CircleAlert size={18} class="mt-0.5 shrink-0" />
					<div class="text-sm">
						<div class="font-medium">Not earning {label}</div>
						{#if afk.serverMessage && afk.serverMessageBlocks}
							<div class="text-amber-200/80">The server says: “{afk.serverMessage}”</div>
						{:else}
							<div class="text-amber-200/80">The counter hasn't gone up since the bot went in.</div>
						{/if}
					</div>
				</div>
			{:else if afk.earning === true}
				<div class="flex items-center gap-3 border-b border-zinc-800 px-4 py-3 text-emerald-300">
					<CircleCheck size={18} class="shrink-0" />
					<div class="min-w-0 text-sm">
						<span class="font-medium">Earning {label}</span>
						{#if afk.serverMessage}<span class="text-zinc-400"> · {afk.serverMessage}</span>{/if}
					</div>
				</div>
			{:else}
				<div class="flex items-center gap-3 border-b border-zinc-800 px-4 py-3 text-zinc-400">
					<Hourglass size={18} class="shrink-0" />
					<div class="min-w-0 text-sm">
						Checking whether it earns…
						{#if afk.serverMessage}<span class="text-zinc-500"> · {afk.serverMessage}</span>{/if}
					</div>
				</div>
			{/if}
		{/if}

		<div class="grid grid-cols-3 divide-x divide-zinc-800 border-b border-zinc-800 text-center">
			<div class="px-2 py-3">
				<div class="text-lg font-semibold text-zinc-100 tabular-nums">{afk?.rewardText ?? amount(afk?.reward)}</div>
				<div class="text-xs text-zinc-500">{label} now</div>
			</div>
			<div class="px-2 py-3">
				<div class="text-lg font-semibold tabular-nums {earned ? 'text-emerald-300' : 'text-zinc-100'}">
					{earned != null ? `+${amount(earned)}` : '-'}
				</div>
				<div class="text-xs text-zinc-500">this stay</div>
			</div>
			<div class="px-2 py-3">
				<div class="text-lg font-semibold text-zinc-100 tabular-nums">{perHour != null ? amount(perHour) : '-'}</div>
				<div class="text-xs text-zinc-500">per hour</div>
			</div>
		</div>

		<div class="divide-y divide-zinc-800">
			<div class="flex items-center gap-4 px-4 py-3">
				<div class="flex-1">
					<div class="text-sm text-zinc-200">Keep in the zone</div>
					<div class="text-xs text-zinc-500">
						Goes in after every connect and whenever it ends up outside, after a short random wait. Pauses while someone plays.
					</div>
				</div>
				<Switch
					label="Keep in the zone"
					checked={!!afk?.keep}
					disabled={!canKeep || busy || !afk}
					onchange={(v) => act('afkKeep', { keep: v })}
				/>
			</div>
			{#if canMove}
				<div class="flex flex-wrap gap-2 px-4 py-3">
					<button
						class="btn-primary"
						disabled={busy || !afk || afk.state === 'OFFLINE' || afk.state === 'IN_ZONE' || afk.state === 'ENTERING'}
						onclick={() => act('afkEnter')}
					>
						<LogIn size={15} /> Go in now
					</button>
					<button class="btn-secondary" disabled={busy || !afk || afk.state === 'OFFLINE'} onclick={() => act('afkLeave')}>
						<LogOut size={15} /> Leave the zone
					</button>
					<span class="self-center text-xs text-zinc-500">
						Uses /{afk?.enterCommand ?? 'afk'} and /{afk?.leaveCommand ?? 'spawn'}. Leaving turns "Keep in the zone" off.
					</span>
				</div>
			{/if}
		</div>
	</section>

	<section class="card">
		<div class="border-b border-zinc-800 px-4 py-3">
			<h2 class="text-sm font-semibold text-zinc-200">{label} over time</h2>
			<p class="text-xs text-zinc-500">The sidebar counter, the last two weeks.</p>
		</div>
		{#if chart && chart.hi === chart.lo}
			<p class="px-4 py-6 text-center text-sm text-zinc-500">{amount(chart.hi)} {label} since {when(chart.from)}. The chart starts once it changes.</p>
		{:else if chart}
			<div class="px-4 py-3">
				<svg viewBox="0 0 {W} {H}" preserveAspectRatio="none" class="h-28 w-full" role="img" aria-label="{label} over time">
					<path d={chart.area} class="fill-emerald-500/10" />
					<path d={chart.line} class="fill-none stroke-emerald-400" stroke-width="2" vector-effect="non-scaling-stroke" />
				</svg>
				<div class="mt-1 flex justify-between text-xs text-zinc-500">
					<span>{when(chart.from)} · {amount(chart.first)}</span>
					<span>now · {amount(chart.last)}</span>
				</div>
			</div>
		{:else}
			<p class="px-4 py-6 text-center text-sm text-zinc-500">
				{history === null ? 'Loading…' : `No ${label} readings yet. They show up once the bot sees the sidebar counter.`}
			</p>
		{/if}

		{#if history?.sessions.length}
			<ul class="divide-y divide-zinc-800 border-t border-zinc-800">
				{#each history.sessions.slice(0, 20) as s (s.start)}
					{@const gain = s.endReward != null && s.startReward != null ? s.endReward - s.startReward : null}
					<li class="flex items-center gap-3 px-4 py-2.5 text-sm">
						<div class="min-w-0 flex-1">
							<div class="text-zinc-200">
								{when(s.start)} · {duration(((s.end ?? now) - s.start) / 1000)}
								{#if !s.end}<span class="ml-1 rounded bg-emerald-900/50 px-1.5 py-0.5 text-xs text-emerald-300">now</span>{/if}
							</div>
							{#if s.endReason}<div class="truncate text-xs text-zinc-500">{s.endReason}</div>{/if}
						</div>
						<div class="shrink-0 tabular-nums {gain ? 'text-emerald-300' : 'text-zinc-500'}">
							{gain != null ? `+${amount(gain)} ${label}` : '-'}
						</div>
					</li>
				{/each}
			</ul>
		{/if}
	</section>

	{#if screen?.sidebar}
		<section class="card">
			<div class="border-b border-zinc-800 px-4 py-3">
				<h2 class="text-sm font-semibold text-zinc-200">What the server shows</h2>
				<p class="text-xs text-zinc-500">The sidebar, as the bot sees it right now.</p>
			</div>
			<div class="px-4 py-3 font-mono text-sm">
				{#if screen.sidebar.title}<div class="mb-1 font-semibold text-zinc-200">{screen.sidebar.title}</div>{/if}
				{#each sidebarLines as line, i (i)}
					<div class="min-h-5 text-zinc-400">{line}</div>
				{/each}
			</div>
		</section>
	{/if}

	{#if canSetup}
		<p class="text-center text-xs text-zinc-500">
			<a class="inline-flex items-center gap-1 text-zinc-400 underline hover:text-zinc-200" href="?tab=settings#settings-afkZone">
				<Settings2 size={13} /> Commands, confirmation text and the reward line
			</a>
			are under Settings → AFK.
		</p>
	{/if}
</div>
