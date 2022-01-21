package net.i2p.i2ptunnel.udpTunnel;

import java.net.InetAddress;

import net.i2p.client.I2PSession;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.Logging;
import net.i2p.i2ptunnel.udp.I2PSource;
//import net.i2p.i2ptunnel.streamr.Pinger;
import net.i2p.i2ptunnel.udp.UDPSink;
import net.i2p.util.EventDispatcher;

/**
 * This is a UDP "Client" which has only a "Sink" for recieving datagrams
 * from the I2P "Source" and sending them to a UDP Client.
 *
 * @author idk
 */

public class I2PTunnelUDPClient extends I2PTunnelUDPClientBase {
    // private final I2PSource source;
    private final UDPSink sink;
    // private final InetAddress UDP_HOSTNAME;
    // private final Pinger pinger;

    public I2PTunnelUDPClient(String host, int port, String destination, Logging l, EventDispatcher notifyThis,
            I2PTunnel tunnel) {
        // super(host, port,
        super(destination, l, notifyThis, tunnel);
        UDPSink _sink = null;
        // I2PSource _source = null;
        // I2PSession session = tunnel.getSession();
        try {
            // _source = new I2PSource(port);
            _sink = new UDPSink(InetAddress.getByName(host), port);
        } catch (Exception e) {
            _sink = null;
            // TODO: Log an error and indicate that the tunnel will fail to start.
        } finally {
            this.sink = _sink;
        }
        // this.pinger = new Pinger(this.sink);
        this.setSink(this.sink);

    }

    @Override
    public final void startRunning() {
        super.startRunning();
        // send subscribe-message
        // this.pinger.start();
        l.log("I2PTunnelUDPClient client ready");
    }

    @Override
    public boolean close(boolean forced) {
        // send unsubscribe-message
        // this.pinger.stop();
        this.sink.stop();
        return super.close(forced);
    }
}
