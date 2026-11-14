package cc.orthrus.quadrature.frame;

/** Lets stages outside this package leave through the state envelope. */
public final class StateEnvelopeAccess {

    private StateEnvelopeAccess() {
    }

    /** Returns state the game's cache does not know to its neutral values. */
    public static void leave() {
        StateEnvelope.leave();
    }
}
