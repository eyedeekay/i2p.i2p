package net.i2p.sam;

/**
 * This is a dummy implementation of a SAMSecureSessionInterface with
 * minimal functionality. It is used for non-GUI applications to allow
 * SAM applications to connect without interactively authenticating.
 *
 * This is the "default" implementation of the SAMSecureSession @interface
 * that behaves exactly like SAM without interactive authentication.
 *
 * @since 1.8.0
 */
public class SAMSecureSession implements SAMSecureSessionInterface {
    private final boolean _useSecureSession;

    public SAMSecureSession() {
        this(true);
    }

    /**
     * Construct with "false" only for testing, as this will disable the SAM API
     * by making it appear that all sessions are canceled by user input. When used
     * as a SAM without interactive authentication, always set this true.
     */
    public SAMSecureSession(boolean enable) {
        _useSecureSession = enable;
    }

    /**
     * In normal usage, this function would seek input from the user, such as with
     * a toast or dialog box. In this function, it simply returns the value of
     * _useSecureSession
     */
    public boolean getSAMUserInput() {
        return _useSecureSession;
    }
}
