package net.i2p.router.networkdb.kademlia;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import net.i2p.data.BlindData;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Job;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.reseed.ReseedChecker;

/**
 * SegmentedNetworkDatabaseFacade
 * 
 * This class implements an interface for managing many netDbs as part of a
 * single I2P instance, each representing it's own view of the network. This
 * allows the I2P clients to operate agnostic of what information the other
 * clients are obtaining from the network database, and prevents information
 * intended for clients from entering the table used by the router for
 * Floodfill operations.
 * 
 * The benefit of this is that we can use this to provide an effective barrier
 * against "Context-Confusion" attacks which exploit the fact that messages sent
 * to clients can update the routing table used by a floodfill, providing
 * evidence that the floodfill hosts the corresponding client. When applied
 * correctly, so that every client uses a unique subDb, the entire class of attack
 * should be neutralized.
 * 
 * The drawback of this is that it makes the netDb less efficient. Clients and
 * Floodfills who share a netDb can update the tables used by those netDbs when
 * a Client encounters an entry obtained by a Floodfill or vice-versa. Clients also
 * must sometimes search the netDb for keys that are owned by other clients or by
 * a co-located Floodfill, if one exists.
 * 
 * In some contexts, it makes sense to view all the tables at once, especially when
 * viewing information from the UI. The functions 'getLeases*', 'getRouters*', and
 * 'getLocalClient*' are provided for this purpose.
 * 
 * In the future, this could also be extended to provide Whanau-like functionality
 * by determining when clients and the local floodfill disagree on the content of a
 * leaseSet.
 * 
 * See implementation: FloodfillNetworkDatabaseSegmentor
 * 
 * @author idk
 * @since 0.9.60
 */
public abstract class SegmentedNetworkDatabaseFacade {
    public SegmentedNetworkDatabaseFacade(RouterContext context) {
        // super(context, null);
    }

    /**
     * Get a sub-netDb
     * 
     * @since 0.9.60
     */
    protected abstract FloodfillNetworkDatabaseFacade getSubNetDB(String dbid);
    protected abstract FloodfillNetworkDatabaseFacade getSubNetDB(Hash dbid);
    public abstract FloodfillNetworkDatabaseFacade mainNetDB();
    public abstract FloodfillNetworkDatabaseFacade multiHomeNetDB();
    public abstract FloodfillNetworkDatabaseFacade clientNetDB(String dbid);
    public abstract FloodfillNetworkDatabaseFacade clientNetDB(Hash dbid);
    public abstract void shutdown();
    public abstract LeaseSet lookupLeaseSetHashIsClient(Hash key);
    protected abstract LeaseSet lookupLeaseSetLocally(Hash key, String dbid);
    public abstract String getDbidByHash(Hash clientKey);
    public abstract Set<FloodfillNetworkDatabaseFacade> getSubNetDBs();
    public abstract List<String> getClients();
    public boolean isInitialized() {
        return mainNetDB().isInitialized();
    }
    public Set<RouterInfo> getRouters() {
        return mainNetDB().getRouters();
    }
    public Set<RouterInfo> getRoutersKnownToClients() {
        Set<RouterInfo> ris = new HashSet<>();
        Set<FloodfillNetworkDatabaseFacade> fndfs = getSubNetDBs();
        for (FloodfillNetworkDatabaseFacade fndf : fndfs) {
            ris.addAll(fndf.getRouters());
        }
        return ris;
    }
    public Set<LeaseSet> getLeasesKnownToClients() {
        Set<LeaseSet> lss = new HashSet<>();
        Set<FloodfillNetworkDatabaseFacade> fndfs = getSubNetDBs();
        for (FloodfillNetworkDatabaseFacade fndf : fndfs) {
            lss.addAll(fndf.getLeases());
        }
        return lss;
    }
    /** @since 0.9.60 */
    public ReseedChecker reseedChecker() {
        return mainNetDB().reseedChecker();
    };
    /**
     * For console ConfigKeyringHelper
     * 
     * @return true if removed
     * @since 0.9.60
     */
    public List<String> lookupClientBySigningPublicKey(SigningPublicKey spk) {
        return Collections.emptyList();
    }
    public List<BlindData> getLocalClientsBlindData() {
        return Collections.emptyList();
    }
}
