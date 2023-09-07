package net.i2p.router;

import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseSegmentor;

public class CleanupNetDbJob extends JobImpl {
    private final RouterContext ctx;

    public CleanupNetDbJob(RouterContext context) {
        super(context);
        ctx = context;
    }

    @Override
    public String getName() {
        return "CleanupNetDbJob";
    }

    @Override
    public void runJob() {
        FloodfillNetworkDatabaseSegmentor fnds = (FloodfillNetworkDatabaseSegmentor) ctx.netDb();
        fnds.removeDeadSubDbs(ctx.clientManager().listClients());
    }

}
