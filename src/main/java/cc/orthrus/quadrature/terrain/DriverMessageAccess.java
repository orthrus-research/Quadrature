package cc.orthrus.quadrature.terrain;

/** Lets stages outside this package mark their calls for the log of driver messages. */
public final class DriverMessageAccess {

    private DriverMessageAccess() {
    }

    /** Asks the driver for its messages, if they were asked for; safe to call every frame. */
    public static void install() {
        DriverMessages.install();
    }

    /** Marks the calls from here to {@link #leave} as a named part of the frame. */
    public static void enter(String name) {
        DriverMessages.enter(name);
    }

    public static void leave() {
        DriverMessages.leave();
    }
}
