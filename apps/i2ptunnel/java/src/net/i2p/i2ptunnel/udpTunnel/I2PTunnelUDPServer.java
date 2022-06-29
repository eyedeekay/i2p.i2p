package net.i2p.i2ptunnel.udpTunnel;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import net.i2p.client.streaming.I2PSocketAddress;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.Logging;
import net.i2p.i2ptunnel.udp.UDPSink;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

/**
 *
 * Client side(I2PTunnelUDPClient.java):
 *
 * - permanent DatagramSocket at e.g. localhost:5353
 * - For EVERY incoming IP datagram request, assign a new I2CP source port,
 * store the source IP/port in a table keyed by the I2CP source port
 * - send a REPLIABLE datagram to the server with the I2CP source port
 *
 * Server side(I2PTunnelUDPServer.java):
 *
 * - receive request, store source I2P Dest/port associated with the request
 * - For EVERY incoming I2P datagram request, open a NEW DatagramSocket on
 * localhost with an EPHEMERAL port. Send the request out the socket and wait
 * for a single reply.
 * - Send reply as a RAW datagram to the stored I2P Dest/port. and CLOSE the
 * DatagramSocket.
 *
 * Client side(I2PTunnelUDPClient.java):
 *
 * - receive reply on the destination I2CP port. Look up source IP/port in the
 * table by the destination I2CP port.
 * - Send reply to the stored IP/port, and remove entry from table.
 *
 * @author idk
 */

public class I2PTunnelUDPServer extends I2PTunnelUDPServerBase {
    private final static int MAX_DATAGRAM_SIZE = 31744;
    private final Log _log = new Log(I2PTunnelUDPServer.class);
    private final InetAddress _localAddress;
    private final int _localPort;
    private Map<Integer, inboundDatagram> _sourceIPPortTable = new HashMap<Integer, inboundDatagram>();

    // outboundReply sends a DatagramPacket to the client over I2P
    private class outboundReply {
        public final I2PSocketAddress _destination;
        public final int _destinationPort;  // I2CP source port
        public final byte[] _reply;
        public int length() {
            return _reply.length;
        }
        public void sendReplyToI2PClient() {
            try {
                _log.debug("Sending reply to I2P client");
                //_socket.send(_reply);
            } catch (Exception e) {
                _log.error("Error sending reply to I2P client", e);
            }
        }
        public outboundReply(I2PSocketAddress destination, int destinationPort, byte[] reply) {
            _destination = destination;
            _destinationPort = destinationPort;
            _reply = reply;
        }

    }

    // inboundDatagram stores the source IP/port associated with a request recieved from I2P
    // and forwards it to a UDP socket on the localhost.
    private class inboundDatagram {
        public final I2PSocketAddress _destination;
        public final int _i2cpPort;
        public final byte[] _request;
        public final DatagramSocket socket;
        public int length() {
            return _request.length;
        }
        // sends the datagram to the local UDP socket, reads a single reply, then closes the DatagramSocket
        public outboundReply sendRequestToLocalhost() {
            //construct a datagram packet
            DatagramPacket packet = new DatagramPacket(_request, _request.length, _localAddress, _localPort);
            try {
                socket.send(packet);
            } catch (Exception e) {
                _log.error("Error sending datagram", e);
            }
            //read the reply
            byte[] reply = new byte[MAX_DATAGRAM_SIZE];
            DatagramPacket replyPacket = new DatagramPacket(reply, reply.length);
            try {
                socket.receive(replyPacket);
            } catch (Exception e) {
                _log.error("Error receiving datagram", e);
            }
            //close the socket
            socket.close();
            //construct the outbound reply
            return new outboundReply(_destination, _i2cpPort, reply);
        }
        public inboundDatagram(I2PSocketAddress dest, int i2cpPort, byte[] data) {
            this._destination = dest;
            this._i2cpPort = i2cpPort;
            this._request = data;
            // create a new DatagramSocket with an EPHEMERAL port
            try {
                this.socket = new DatagramSocket();
            } catch (Exception e) {
                _log.error("Error creating DatagramSocket", e);
                throw new RuntimeException(e);
            }
        }
    }


    // Constructor, host is localhost(usually) or the host of the UDP client, port
    // is the port of the UDP client
    public I2PTunnelUDPServer(String host, int port, File privKeyFile, String privKeyPath, Logging l, EventDispatcher notifyThis,
            I2PTunnel tunnel) {
            super(privKeyFile, privKeyPath, l, notifyThis, tunnel);
            try {
                _localAddress = InetAddress.getByName(host);
            } catch (Exception e) {
                _log.error("Error getting InetAddress for host: " + host, e);
                throw new RuntimeException(e);
            }
            _localPort = port;
    }

    //private inboundDatagram receiveInboundI2PDatagram() {

    //}

    @Override
    public final void startRunning() {
        super.startRunning();
        while (true) {
        }
    }

    @Override
    public boolean close(boolean forced) {
        return super.close(forced);
    }
}
