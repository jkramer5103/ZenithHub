package com.zenith.terminal;

import com.zenith.control.ControlServer;
import com.zenith.event.console.ConsoleLogEvent;

import static com.zenith.Globals.EVENT_BUS;

/**
 * Prints log output to stdout. There is no interactive terminal: commands come from the manager.
 */
public class TerminalManager {
    public void start() {
        EVENT_BUS.subscribe(this, ConsoleLogEvent.class, this::writeTerminalOutput);
    }

    public void stop() {
        EVENT_BUS.unsubscribe(this);
    }

    private void writeTerminalOutput(ConsoleLogEvent event) {
        if (ControlServer.INSTANCE.hasSubscribers()) return;
        var str = event.ansi();
        if (str.endsWith("\n") || str.endsWith("\n\033[m") || str.endsWith("\n\033[0m")) {
            System.out.print(str);
        } else {
            System.out.println(str);
        }
    }
}
