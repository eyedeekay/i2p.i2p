package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.crypto.SigAlgo;
import net.i2p.crypto.SigType;
import net.i2p.data.BlindData;
import net.i2p.data.Certificate;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Hash;
import net.i2p.data.KeyCertificate;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.kademlia.RejectTrimmer;
import net.i2p.kademlia.SelectionCollector;
import net.i2p.router.Banlist;
import net.i2p.router.Job;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.networkdb.PublishLocalRouterInfoJob;
import net.i2p.router.networkdb.reseed.ReseedChecker;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

/**
 * Kademlia based version of the network database, with segmentation
 * between sources.
 * Never instantiated directly; see FloodfillNetworkDatabaseFacade.
 */
//public abstract class KademliaNetworkDatabaseFacade extends KademliaNetworkDatabaseContext {
public abstract class KademliaNetworkDatabaseFacade extends KademliaNetworkDatabaseContext {
    public KademliaNetworkDatabaseFacade(RouterContext context) {
        super(context);
    }
}
