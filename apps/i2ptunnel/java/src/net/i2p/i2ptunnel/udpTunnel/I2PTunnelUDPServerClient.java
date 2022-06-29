package net.i2p.i2ptunnel.udpTunnel;

import java.io.File;

import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelTask;
import net.i2p.i2ptunnel.Logging;
import net.i2p.i2ptunnel.udp.Sink;
import net.i2p.i2ptunnel.udp.Source;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

/**
 *
 * * Client side:
 *
 * - permanent DatagramSocket at e.g. localhost:5353
 * - For EVERY incoming IP datagram request, assign a new I2CP source port,
 * store the source IP/port in a table keyed by the I2CP source port
 * - send a REPLIABLE datagram to the server with the I2CP source port
 *
 * Server side:
 *
 * - receive request, store source I2P Dest/port associated with the request
 * - For EVERY incoming I2P datagram request, open a NEW DatagramSocket on
 * localhost with an EPHEMERAL port. Send the request out the socket and wait
 * for a single reply.
 * - Send reply as a RAW datagram to the stored I2P Dest/port. and CLOSE the
 * DatagramSocket.
 *
 * Client side:
 *
 * - receive reply on the destination I2CP port. Look up source IP/port in the
 * table by the destination I2CP port.
 * - Send reply to the stored IP/port, and remove entry from table.
 *
 * @author idk
 */
public class I2PTunnelUDPServerClient extends I2PTunnelTask implements Source, Sink {
    private final Log _log = new Log(I2PTunnelUDPServerClient.class);

    public I2PTunnelUDPServerClient(String host, int port, File privkey, String privkeyname, Logging l,
            EventDispatcher notifyThis,
            I2PTunnel tunnel) {
        super("UDPPeer <- " + privkeyname, notifyThis, tunnel);
        //super(privkey, privkeyname, l, notifyThis, tunnel);
    }

    public final void start() {
        //super.startRunning();
        if (_log.shouldInfo())
            _log.info("I2PTunnelUDPServer server ready");
    }

    @Override
    public boolean close(boolean forced) {
        return forced;
        //return super.close(forced);
    }

    @Override
    public void send(Destination src, int fromPort, int toPort, byte[] data) {
        // TODO
    }

    @Override
    public void setSink(Sink sink) {
        // TODO
    }
}
