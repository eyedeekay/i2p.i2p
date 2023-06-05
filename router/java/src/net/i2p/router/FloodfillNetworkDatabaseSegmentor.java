package net.i2p.router;

import java.util.HashMap;
import java.util.Map;

import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;

//public class FloodfillNetworkDatabaseSegmentor extends FloodfillNetworkDatabaseFacade {
public class FloodfillNetworkDatabaseSegmentor {
    private final RouterContext _context;
    private FloodfillNetworkDatabaseFacade _primaryDB;
    private Map<String, FloodfillNetworkDatabaseFacade> _subDBs = new HashMap<String, FloodfillNetworkDatabaseFacade>();
    public FloodfillNetworkDatabaseSegmentor(RouterContext context) {
        _context = context;
        _primaryDB = new FloodfillNetworkDatabaseFacade(context);
    }
    public FloodfillNetworkDatabaseFacade getSubNetDB(String id){
        if (id == null || id == "") {
            return _primaryDB;
        }
        FloodfillNetworkDatabaseFacade subdb =_subDBs.get(id);
        if (subdb != null) {
            subdb = new FloodfillNetworkDatabaseFacade(_context);
            _subDBs.put(id, subdb);
        }
        return subdb;
    }
}
