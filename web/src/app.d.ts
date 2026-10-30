// See https://svelte.dev/docs/kit/types#app.d.ts
// for information about these interfaces
declare global {
	// the loading skeleton in app.html
	var zenithBoot: { loading(on: boolean): void } | undefined;

	namespace App {
		// interface Error {}
		// interface Locals {}
		// interface PageData {}
		// interface PageState {}
		// interface Platform {}
	}
}

export {};
