package com.zenith.manager.state;

import com.zenith.manager.util.Json;
import com.zenith.manager.util.SecureFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Single lock around the persisted state. Reads get a consistent view; updates are saved atomically.
 */
public final class StateStore {
    private final Path file;
    private final State state;

    public StateStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            var loaded = Json.GSON.fromJson(Files.readString(file), State.class);
            this.state = loaded != null ? loaded : new State();
        } else {
            this.state = new State();
        }
        normalize();
    }

    public synchronized <T> T read(Function<State, T> reader) {
        return reader.apply(state);
    }

    public synchronized void update(Consumer<State> mutator) {
        mutator.accept(state);
        save();
    }

    public synchronized <T> T updateAndGet(Function<State, T> mutator) {
        var result = mutator.apply(state);
        save();
        return result;
    }

    private void save() {
        try {
            SecureFiles.writeString(file, Json.GSON.toJson(state));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed saving " + file, e);
        }
    }

    // fill collections that may be missing in hand-edited or older files
    private void normalize() {
        if (state.globalSettings == null) state.globalSettings = new com.google.gson.JsonObject();
        if (state.settings == null) state.settings = new State.ManagerSettings();
        for (var s : state.servers) if (s.settings == null) s.settings = new com.google.gson.JsonObject();
        for (var i : state.instances) {
            if (i.settings == null) i.settings = new com.google.gson.JsonObject();
            // Gson loads lists as ArrayList; grants are read without the lock, see State.Instance#grants
            i.grants = new java.util.concurrent.CopyOnWriteArrayList<>(i.grants == null ? java.util.List.of() : i.grants);
            if (i.autostart == null) i.autostart = State.Autostart.RESTORE;
        }
        for (var u : state.users) if (u.mcNames == null) u.mcNames = new java.util.ArrayList<>();
        // version 1 had no owners: everything belonged to the admin
        var admin = state.users.stream().filter(u -> u.admin).findFirst().map(u -> u.id).orElse(null);
        for (var a : state.accounts) if (a.ownerId == null) a.ownerId = admin;
        for (var i : state.instances) {
            if (i.ownerId == null) {
                i.ownerId = state.accounts.stream().filter(a -> a.id.equals(i.accountId)).findFirst().map(a -> a.ownerId).orElse(admin);
            }
        }
        state.version = 2;
    }
}
