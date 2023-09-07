package net.i2p.router.networkdb;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseSegmentor;

public class CleanupNetDbJob extends JobImpl {
    private final RouterContext ctx;
    private static final long RERUN_DELAY_MS = 1 * 60 * 1000;

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
        requeue(RERUN_DELAY_MS);
    }

}
