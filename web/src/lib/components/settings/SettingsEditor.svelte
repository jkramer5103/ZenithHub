<script lang="ts">
	import { api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
		import { catalog, curatedPaths, humanize, humanizeEnum, type Field, type Group, type Section } from '#lib/settings-catalog.js';
	import { matchedWords, parseQuery, score, type Haystack } from '#lib/settings-search.js';
	import { toasts } from '#lib/toast.svelte.js';
	import type { SettingScope, SettingsView } from '#lib/types.js';
	import { Check, Code, Lock, LoaderCircle, RotateCcw, Search, X } from '@lucide/svelte';
		import Highlight from './Highlight.svelte';
	import SettingRow from './SettingRow.svelte';
	import Switch from './Switch.svelte';

	let { endpoint, scope }: { endpoint: string; scope: SettingScope } = $props();

	let view = $state<SettingsView | null>(null);
	let loadError = $state('');
	let pending = $state(0);
	let query = $state('');
	let onlyChanged = $state(false);

	async function load() {
		try {
			view = await api.get<SettingsView>(endpoint);
			loadError = '';
		} catch (e) {
			loadError = e instanceof Error ? e.message : String(e);
		}
	}
	load();

	// one request at a time, so responses can't arrive out of order
	let queue: Promise<void> = Promise.resolve();
	function patch(body: { set?: Record<string, unknown>; reset?: string[] }) {
		pending++;
		queue = queue.then(async () => {
			try {
				view = await api.patch<SettingsView>(endpoint, body);
			} catch (e) {
				toasts.error(e);
				await load();
			} finally {
				pending--;
			}
		});
	}

	function set(path: string, value: unknown) {
		if (view) view.values[path] = value;
		patch({ set: { [path]: value } });
	}

	function reset(path: string) {
		patch({ reset: [path] });
	}

	const changedCount = $derived(view ? Object.values(view.sources).filter((s) => s === scope).length : 0);

	// --- search and filtering ---

	const search = $derived(parseQuery(query));
	const searching = $derived(search.words.length > 0);
	let searchInput = $state<HTMLInputElement>();

	function sectionText(group: Group, section: Section) {
		return [
			{ text: section.title, weight: 2 },
			{ text: section.description, weight: 1 },
			{ text: group.title, weight: 1.5 }
		];
	}

	function fieldText(f: Field): Haystack[] {
		const type = view!.schema[f.path];
		const options = f.options ? Object.values(f.options) : type.type === 'enum' ? (type.values ?? []).map(humanizeEnum) : [];
		return [
			{ text: f.label, weight: 3 },
			{ text: f.help ?? '', weight: 1.5 },
			{ text: options.join(' '), weight: 1 },
			{ text: f.path, weight: 0.8 }
		];
	}

	function fieldScore(f: Field, section: Section, group: Group) {
		if (!view || !(f.path in view.schema)) return 0;
		if (onlyChanged && view.sources[f.path] !== scope) return 0;
		return score(search, [...fieldText(f), ...sectionText(group, section)]);
	}

	function shownByCondition(f: Field) {
		// a search shows every match, even settings that only apply once something else is on
		if (searching || !f.showIf || !view) return true;
		return typeof f.showIf === 'string' ? view.values[f.showIf] === true : f.showIf(view.values);
	}

	interface SectionResult {
		section: Section;
		fields: Field[];
		score: number;
	}

	/** the catalog narrowed to what matches, best matches first while searching */
	const groups = $derived.by(() => {
		if (!view) return [];
		const v = view;
		const result: (Group & { results: SectionResult[]; score: number })[] = [];
		for (const group of catalog) {
			const results: SectionResult[] = [];
			for (const section of group.sections) {
				let scored = section.fields.map((f) => ({ f, s: fieldScore(f, section, group) })).filter((x) => x.s > 0);
				if (searching) {
					// when the section's name matches, keep only its settings whose own wording (not their config path) matches a word, if any do
					const own = scored.filter((x) => matchedWords(search, fieldText(x.f).slice(0, 3)) > 0);
					if (own.length) scored = own;
				}
				if (searching) scored.sort((a, b) => b.s - a.s);
				const hasToggle = !!section.toggle && section.toggle in v.schema;
				const toggleScore =
					hasToggle && (!onlyChanged || v.sources[section.toggle!] === scope) ? score(search, sectionText(group, section)) : 0;
				if (!scored.length && !toggleScore) continue;
				results.push({ section, fields: scored.map((x) => x.f), score: Math.max(toggleScore, ...scored.map((x) => x.s)) });
			}
			if (!results.length) continue;
			if (searching) results.sort((a, b) => b.score - a.score);
			result.push({ ...group, results, score: Math.max(...results.map((r) => r.score)) });
		}
		if (searching) result.sort((a, b) => b.score - a.score);
		return result;
	});

	/** every setting not in the curated catalog, grouped by its config prefix */
	const rawGroups = $derived.by(() => {
		if (!view || !app.devMode) return [];
		const groups = new Map<string, string[]>();
		for (const path of Object.keys(view.schema)) {
			if (curatedPaths.has(path) && !searching) continue;
			if (onlyChanged && view.sources[path] !== scope) continue;
			if (searching && !score(search, [{ text: path, weight: 1 }])) continue;
			const prefix = path.split('.').slice(0, -1).join('.') || 'general';
			if (!groups.has(prefix)) groups.set(prefix, []);
			groups.get(prefix)!.push(path);
		}
		return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b));
	});

	const matchCount = $derived(groups.reduce((n, g) => n + g.results.reduce((m, r) => m + Math.max(1, r.fields.length), 0), 0));

	function onKeydown(e: KeyboardEvent) {
		const target = e.target as HTMLElement | null;
		const typing = !!target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName));
		if ((e.key === 'k' && (e.metaKey || e.ctrlKey)) || (e.key === '/' && !typing)) {
			e.preventDefault();
			searchInput?.focus();
			searchInput?.select();
		}
	}

	function onSearchKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			if (query) query = '';
			else searchInput?.blur();
		} else if (e.key === 'Enter') {
			// jump to the best match
			const first = groups[0]?.results[0];
			if (first) document.getElementById(`settings-${first.section.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
		}
	}

	function scrollTo(id: string) {
		document.getElementById(`settings-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
	}

	const applyNote = $derived(
		!view
			? ''
			: scope === 'instance'
				? view.live
					? 'Changes apply right away.'
					: 'Changes apply when the instance starts.'
				: 'Changes apply to running instances right away, unless an instance overrides them.'
	);
</script>

<svelte:window onkeydown={onKeydown} />

{#snippet sectionCard(section: Section, matched: Field[])}
	{@const v = view!}
	{@const hasToggle = !!section.toggle && section.toggle in v.schema}
	{@const enabled = !hasToggle || v.values[section.toggle!] === true}
	{@const toggleForced = hasToggle && v.sources[section.toggle!] === 'forced'}
	{@const toggleChanged = hasToggle && v.sources[section.toggle!] === scope}
		{@const fields = matched.filter(shownByCondition)}
	<section class="card" id="settings-{section.id}">
		<div class="flex items-start gap-4 p-4">
			<div class="min-w-0 flex-1">
				<div class="flex flex-wrap items-center gap-2">
					<h3 class="font-medium"><Highlight text={section.title} query={search} /></h3>
					{#if toggleChanged}
						<span class="rounded bg-emerald-950 px-1.5 py-px text-[11px] font-medium text-emerald-300">Changed</span>
						<button
							type="button"
							class="rounded p-0.5 text-zinc-500 hover:bg-zinc-800 hover:text-zinc-200"
							title="Reset to the inherited value"
							aria-label="Reset {section.title}"
							onclick={() => reset(section.toggle!)}
						>
							<RotateCcw size={13} />
						</button>
					{/if}
				</div>
				<p class="mt-0.5 text-sm text-zinc-400"><Highlight text={section.description} query={search} /></p>
				{#if toggleForced}
					<p class="mt-1 flex items-center gap-1 text-xs text-zinc-500">
						<Lock size={11} />
						{section.toggle === 'client.connectionProxy.enabled' ? 'Outbound proxies are turned off in the manager settings.' : 'Managed by ZenithProxy'}
					</p>
				{/if}
			</div>
			{#if hasToggle}
				<div class="pt-0.5">
					<Switch
						checked={enabled}
						disabled={toggleForced}
						label={section.title}
						onchange={(on) => set(section.toggle!, on)}
					/>
				</div>
			{/if}
		</div>
		{#if (enabled || searching) && fields.length}
			<div class="divide-y divide-zinc-800/80 border-t border-zinc-800 px-4">
				{#each fields as field (field.path)}
					<SettingRow
						{field}
						{scope}
						type={v.schema[field.path]}
						value={v.values[field.path]}
												source={v.sources[field.path]}
						query={search}
						onset={(value) => set(field.path, value)}
						onreset={() => reset(field.path)}
					/>
				{/each}
			</div>
		{/if}
	</section>
{/snippet}

{#if loadError}
	<div class="card p-6 text-center text-sm text-zinc-400">
		{loadError}
		<div class="mt-3"><button class="btn-secondary" onclick={load}>Try again</button></div>
	</div>
{:else if !view}
	<div class="flex items-center justify-center gap-2 py-16 text-sm text-zinc-400">
		<LoaderCircle size={18} class="animate-spin" /> Loading settings…
	</div>
{:else}
	<div class="grid gap-6 lg:grid-cols-[180px_1fr]">
		<nav class="hidden lg:block">
			<div class="sticky top-20 space-y-0.5">
				{#each groups as group (group.id)}
					<button
						class="btn w-full justify-start px-2.5 py-1.5 text-zinc-400 hover:bg-zinc-900 hover:text-zinc-100"
						onclick={() => scrollTo(group.id)}
					>
						<group.icon size={16} />
						{group.title}
					</button>
				{/each}
				{#if rawGroups.length}
					<button
						class="btn w-full justify-start px-2.5 py-1.5 text-zinc-400 hover:bg-zinc-900 hover:text-zinc-100"
						onclick={() => scrollTo('raw')}
					>
						<Code size={16} /> All settings
					</button>
				{/if}
			</div>
		</nav>

		<div class="min-w-0 space-y-8">
							<div class="sticky top-14 z-20 -mx-1 space-y-2 bg-zinc-950 px-1 py-2">
					<div class="flex flex-wrap items-center gap-2">
					<div class="relative min-w-0 flex-1">
						<Search size={16} class="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-zinc-500" />
						<input
								bind:this={searchInput}
								type="search"
								class="input pr-16 pl-9 [&::-webkit-search-cancel-button]:hidden"
								placeholder="Search settings"
								autocomplete="off"
								spellcheck="false"
								bind:value={query}
								onkeydown={onSearchKeydown}
								aria-label="Search settings"
							/>
							{#if !query}
								<kbd
									class="pointer-events-none absolute top-1/2 right-2.5 hidden -translate-y-1/2 rounded border border-zinc-700 px-1.5 font-mono text-[11px] text-zinc-500 sm:block"
									>/</kbd
								>
							{/if}
						{#if query}
							<button
								class="absolute top-1/2 right-2 -translate-y-1/2 rounded p-1 text-zinc-500 hover:text-zinc-200"
								aria-label="Clear search"
								onclick={() => (query = '')}><X size={14} /></button
							>
						{/if}
					</div>
					{#if changedCount}
						<button
							class="btn border px-3 py-2 text-sm {onlyChanged
								? 'border-emerald-700 bg-emerald-950/60 text-emerald-200'
								: 'border-zinc-700 text-zinc-300 hover:bg-zinc-900'}"
							onclick={() => (onlyChanged = !onlyChanged)}
						>
							{changedCount} changed
						</button>
					{/if}
				</div>
									<p class="flex items-center gap-1.5 text-xs text-zinc-500">
						{#if searching}
							{matchCount
								? `${matchCount} matching setting${matchCount === 1 ? '' : 's'}. Press Enter to jump to the best match.`
								: ''}
						{:else if pending}
						<LoaderCircle size={13} class="animate-spin" /> Saving…
					{:else}
						<Check size={13} class="text-emerald-500" /> Saved. {applyNote}
					{/if}
				</p>
			</div>

			{#each groups as group (group.id)}
				<div id="settings-{group.id}" class="scroll-mt-20 space-y-3">
					<h2 class="flex items-center gap-2 text-sm font-semibold tracking-wide text-zinc-400 uppercase">
						<group.icon size={15} />
						{group.title}
					</h2>
					{#each group.results as r (r.section.id)}
						{@render sectionCard(r.section, r.fields)}
					{/each}
				</div>
			{/each}

			{#if rawGroups.length}
				<div id="settings-raw" class="scroll-mt-20 space-y-3">
					<h2 class="flex items-center gap-2 text-sm font-semibold tracking-wide text-zinc-400 uppercase">
						<Code size={15} /> All settings
					</h2>
					<p class="text-sm text-zinc-500">Every Zenith setting, by its config path. Shown because developer mode is on.</p>
					{#each rawGroups as [prefix, paths] (prefix)}
						<section class="card">
							<h3 class="border-b border-zinc-800 px-4 py-2.5 font-mono text-xs text-zinc-400">{prefix}</h3>
							<div class="divide-y divide-zinc-800/80 px-4">
								{#each paths as path (path)}
									<SettingRow
										field={{ path, label: path.split('.').at(-1) ?? path, help: humanize(path) }}
										{scope}
										mono
										type={view.schema[path]}
										value={view.values[path]}
										source={view.sources[path]}
										onset={(value) => set(path, value)}
										onreset={() => reset(path)}
									/>
								{/each}
							</div>
						</section>
					{/each}
				</div>
			{/if}

			{#if !groups.length && !rawGroups.length}
				<div class="py-12 text-center text-sm text-zinc-400">
					{#if onlyChanged && !searching}
						Nothing changed here yet.
					{:else}
						No settings match “{query.trim()}”.
						{#if onlyChanged}<button class="ml-1 text-emerald-400 underline" onclick={() => (onlyChanged = false)}>Search all settings</button>{/if}
					{/if}
				</div>
			{/if}
		</div>
	</div>
{/if}
