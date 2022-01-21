package net.i2p.i2ptunnel.udpTunnel;

import java.io.File;
import java.net.InetAddress;

import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.Logging;
import net.i2p.i2ptunnel.streamr.Pinger;
import net.i2p.i2ptunnel.udp.UDPSink;
import net.i2p.i2ptunnel.udp.UDPSource;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

/**
 * This is a UDP "Server" which is a UDP tunnel which has both a "Sink" for
 * sending and a "Source" for receiving.
 *
 * @author idk
 */
public class I2PTunnelUDPServer extends I2PTunnelUDPServerBase {
    private final Log _log = new Log(I2PTunnelUDPServer.class);
    private final UDPSink sink;
    private final UDPSource source;
    private final InetAddress UDP_HOSTNAME;
    private final int UDP_PORT;

    public I2PTunnelUDPServer(String host, int port, File privkey, String privkeyname, Logging l,
            EventDispatcher notifyThis,
            I2PTunnel tunnel) {
        super(privkey, privkeyname, l, notifyThis, tunnel);
        // File privkey, String privkeyname, Logging l,EventDispatcher notifyThis,
        // I2PTunnel tunnel
        InetAddress _udpHostname = null;
        try {
            _udpHostname = InetAddress.getByName(host);
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Failed to resolve hostname, using default(localhost): " + host, e);
            try {
                _udpHostname = InetAddress.getLocalHost();
            } catch (Exception crite) {
                if (_log.shouldLog(Log.ERROR))
                    _log.warn("Failed to resolve localhost, UDP tunnel will fail.: " + host, crite);
                _udpHostname = null;
            }
        } finally {
            if (_udpHostname == null) {
                _log.error("Failed to resolve UDP hostname: " + host);
            }
        }
        this.UDP_HOSTNAME = _udpHostname;
        this.UDP_PORT = port;
        this.sink = new UDPSink(this.UDP_HOSTNAME, this.UDP_PORT);
        this.source = new UDPSource(this.UDP_PORT);
        this.setSink(this.sink);
    }

    @Override
    public final void startRunning() {
        super.startRunning();
        // send subscribe-message
        l.log("I2PTunnelUDPServer server ready");
    }

    @Override
    public boolean close(boolean forced) {
        // send unsubscribe-message
        this.sink.stop();
        return super.close(forced);
    }
}
