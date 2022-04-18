package net.i2p.sam;

/**
 * SAMSecureSessionInterface is used for implementing interactive authentication
 * to SAM applications. It needs to be implemented by a class for Desktop and
 * Android applications and passed to the SAM bridge when constructed.
 */
public interface SAMSecureSessionInterface {
    public boolean getSAMUserInput();
}
