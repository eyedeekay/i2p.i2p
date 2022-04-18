package net.i2p.sam;

/**
 * This is a dummy implementation of a SAMSecureSessionInterface with
 * minimal functionality. It is used for non-GUI applications to allow
 * SAM applications to connect without interactively authenticating.
 */
public class SAMSecureSession implements SAMSecureSessionInterface {
    private final boolean _useSecureSession;

    public SAMSecureSession(boolean enable) {
        _useSecureSession = enable;
    }

    public boolean getSAMUserInput() {
        if (_useSecureSession) {
            return true;
        }
        return false;
    }
}
