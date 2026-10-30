<script lang="ts">
	import '../app.css';
	import { app } from '#lib/app.svelte.js';
	import AuthScreen from '#lib/components/AuthScreen.svelte';
	import Logo from '#lib/components/Logo.svelte';
	import PasswordModal from '#lib/components/PasswordModal.svelte';
	import DiscordLinkModal from '#lib/components/DiscordLinkModal.svelte';
	import McLinkModal from '#lib/components/McLinkModal.svelte';
	import Toasts from '#lib/components/Toasts.svelte';
	import Switch from '#lib/components/settings/Switch.svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { ChevronDown, Code, Gamepad2, KeyRound, LayoutGrid, LogOut, MessageSquare, RotateCw, Server, Settings, UserRound, Users, WifiOff } from '@lucide/svelte';
	import type { LayoutProps } from './$types';

	let { children }: LayoutProps = $props();

	let loadError = $state('');
	function load() {
		loadError = '';
		app.loadSession().catch((e) => (loadError = e instanceof Error ? e.message : String(e)));
	}
	load();

	// app.html's skeleton covers the page until there is something to show
	const loading = $derived(!loadError && (!app.session || (!!app.user && !app.overview)));
	$effect(() => globalThis.zenithBoot?.loading(loading));

	const nav = $derived([
		{ href: '/', label: 'Instances', icon: LayoutGrid, show: true },
		{ href: '/accounts', label: 'Accounts', icon: UserRound, show: true },
		{ href: '/servers', label: 'Servers', icon: Server, show: true },
		{ href: '/users', label: 'Users', icon: Users, show: app.isAdmin },
		{ href: '/settings', label: 'Settings', icon: Settings, show: app.isAdmin }
	].filter((n) => n.show));

	// the nav hides these from members; a typed URL or old bookmark goes home instead of failing to load
	const adminOnly = $derived(!!app.user && !app.isAdmin && /^\/(users|settings)(\/|$)/.test(page.url.pathname));
	$effect(() => {
		if (adminOnly) void goto('/', { replaceState: true });
	});

	let menuOpen = $state(false);
	let changingPassword = $state(false);
	let linkingDiscord = $state(false);
	let linkingMinecraft = $state(false);

	function closeMenuOutside(e: MouseEvent) {
		if (menuOpen && !(e.target as HTMLElement).closest('[data-user-menu]')) menuOpen = false;
	}

	function active(href: string) {
		const path = page.url.pathname;
		return href === '/' ? path === '/' || path.startsWith('/instances') : path.startsWith(href);
	}
</script>

<svelte:window onclick={closeMenuOutside} onkeydown={(e) => e.key === 'Escape' && (menuOpen = false)} />

{#if loadError}
	<div class="flex min-h-dvh items-center justify-center p-6">
		<div class="card w-full max-w-sm p-6 text-center" role="alert">
			<div class="flex justify-center"><Logo /></div>
			<h1 class="mt-3 font-semibold">Can't reach ZenithHub</h1>
			<p class="mt-1 text-sm text-zinc-400">{loadError}</p>
			<button class="btn-primary mt-4" onclick={load}><RotateCw size={16} /> Try again</button>
		</div>
	</div>
{:else if !app.session}
	<!-- still loading: the skeleton covers this -->
{:else if !app.user}
	<AuthScreen />
{:else if app.overview}
	<div class="flex min-h-dvh flex-col pb-[calc(4rem+env(safe-area-inset-bottom))] md:pb-0">
		<header class="sticky top-0 z-30 border-b border-zinc-800/80 bg-zinc-950/85 backdrop-blur">
			<div class="mx-auto flex h-14 max-w-6xl items-center gap-6 px-4">
				<a href="/" class="flex items-center gap-2 font-semibold"><Logo /> ZenithHub</a>
				<nav class="hidden items-center gap-1 md:flex">
					{#each nav as item}
						<a
							href={item.href}
							class="btn px-3 py-1.5 {active(item.href) ? 'bg-zinc-800 text-zinc-100' : 'text-zinc-400 hover:text-zinc-100'}"
						>
							<item.icon size={16} />
							{item.label}
						</a>
					{/each}
				</nav>
				<div class="relative ml-auto" data-user-menu>
					<button
						class="btn-ghost gap-2 px-2 py-1.5"
						aria-haspopup="menu"
						aria-expanded={menuOpen}
						onclick={() => (menuOpen = !menuOpen)}
					>
						<span class="flex size-7 items-center justify-center rounded-full bg-zinc-800 text-xs font-semibold text-zinc-200 uppercase">
							{app.user.username.charAt(0)}
						</span>
						<span class="hidden text-sm text-zinc-300 sm:inline">{app.user.username}</span>
						<ChevronDown size={14} class="text-zinc-500" />
					</button>
					{#if menuOpen}
						<div class="absolute right-0 mt-2 w-72 rounded-xl border border-zinc-800 bg-zinc-900 p-1.5 shadow-2xl" role="menu">
							<div class="px-3 py-2">
								<div class="text-sm font-medium">{app.user.username}</div>
								<div class="text-xs text-zinc-500">{app.isAdmin ? 'Administrator' : 'Member'}</div>
							</div>
							<div class="mx-3 mb-1 border-t border-zinc-800"></div>
							<div class="flex items-center gap-3 rounded-lg px-3 py-2.5">
								<Code size={16} class="shrink-0 text-zinc-400" />
								<div class="min-w-0 flex-1">
									<div class="text-sm">Developer mode</div>
									<div class="text-xs text-zinc-500">Raw console and every setting</div>
								</div>
								<Switch checked={app.devMode} label="Developer mode" onchange={(on) => app.setDevMode(on)} />
							</div>
							<button
								class="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left text-sm hover:bg-zinc-800"
								role="menuitem"
								onclick={() => {
									menuOpen = false;
									changingPassword = true;
								}}
							>
								<KeyRound size={16} class="text-zinc-400" /> Change password
							</button>
							<button
								class="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left text-sm hover:bg-zinc-800"
								role="menuitem"
								onclick={() => {
									menuOpen = false;
									linkingMinecraft = true;
								}}
							>
								<Gamepad2 size={16} class="text-zinc-400" />
								Minecraft accounts{app.user.mcNames.length ? ` (${app.user.mcNames.length})` : ''}
							</button>
							{#if app.overview?.discordAvailable || app.user.discordLinked}
								<button
									class="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left text-sm hover:bg-zinc-800"
									role="menuitem"
									onclick={() => {
										menuOpen = false;
										linkingDiscord = true;
									}}
								>
									<MessageSquare size={16} class="text-zinc-400" />
									{app.user.discordLinked ? 'Discord: linked' : 'Link Discord'}
								</button>
							{/if}
							<button
								class="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left text-sm hover:bg-zinc-800"
								role="menuitem"
								onclick={() => app.logout()}
							>
								<LogOut size={16} class="text-zinc-400" /> Sign out
							</button>
						</div>
					{/if}
				</div>
			</div>
		</header>

		{#if !app.live}
			<div class="flex items-center justify-center gap-2 bg-amber-950 px-4 py-1.5 text-xs text-amber-200">
				<WifiOff size={14} /> Connection lost, reconnecting…
			</div>
		{/if}

		<main class="mx-auto flex w-full max-w-6xl flex-1 flex-col px-4 py-6">
			{#if !adminOnly}{@render children()}{/if}
		</main>

		{#if nav.length > 1}
			<nav
				class="fixed inset-x-0 bottom-0 z-30 flex border-t border-zinc-800 bg-zinc-950/95 pb-[env(safe-area-inset-bottom)] backdrop-blur md:hidden"
			>
				{#each nav as item}
					<a
						href={item.href}
						class="flex flex-1 flex-col items-center gap-0.5 py-2 text-[11px] {active(item.href) ? 'text-emerald-400' : 'text-zinc-500'}"
					>
						<item.icon size={20} />
						{item.label}
					</a>
				{/each}
			</nav>
		{/if}
	</div>
{/if}

{#if changingPassword}<PasswordModal onclose={() => (changingPassword = false)} />{/if}
{#if linkingMinecraft}<McLinkModal onclose={() => (linkingMinecraft = false)} />{/if}
{#if linkingDiscord}<DiscordLinkModal onclose={() => (linkingDiscord = false)} />{/if}
<Toasts />
