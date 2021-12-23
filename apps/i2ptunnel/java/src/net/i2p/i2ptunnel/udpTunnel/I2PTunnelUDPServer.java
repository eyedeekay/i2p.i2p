package net.i2p.i2ptunnel.udpTunnel;

import net.i2p.i2ptunnel.streamr.Pinger;
import net.i2p.i2ptunnel.udp.UDPSink;

public class I2PTunnelUDPServer extends I2PTunnelUDPServerBase {

        public I2PTunnelUDPServer(String host, int port, String destination, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
            super(host, port, destination, l, notifyThis, tunnel);
        }

        @Override
        public final void startRunning() {
            super.startRunning();
            // send subscribe-message
            this.pinger.start();
            l.log("I2PTunnelUDPServer server ready");
        }

        @Override
        public boolean close(boolean forced) {
            // send unsubscribe-message
            this.pinger.stop();
            this.sink.stop();
            return super.close(forced);
        }

        private final UDPSink sink;
        private final Pinger pinger;

}
