package com.zenith.manager.auth;

/**
 * What a user may do with one instance.
 */
public enum Capability {
    /** See status, console and chat. */
    VIEW,
    /** Join in-game as a spectator. */
    SPECTATE,
    /** Join in-game and control the account. */
    PLAY,
    /** Send chat messages as the account. */
    CHAT,
    /** Start, stop, connect and disconnect. */
    CONTROL,
    /** Run any Zenith command. */
    COMMANDS,
    /** Edit instance settings. */
    SETTINGS,
    /** Grant other people access to this instance. */
    SHARE
}
