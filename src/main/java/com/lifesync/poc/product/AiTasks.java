package com.lifesync.poc.product;

import java.util.concurrent.*;

/** Bound slow model work so repeated requests cannot fill all web threads or an unbounded queue. */
final class AiTasks {
    private static final ExecutorService WORKERS=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
        new SynchronousQueue<>(),task->{Thread t=new Thread(task,"ui-ai");t.setDaemon(true);return t;});
    private AiTasks() {}
    static <T> T run(Callable<T> task) throws Exception {
        Future<T> future=WORKERS.submit(task);
        try { return future.get(15,TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw ex; }
        finally { if(!future.isDone()) future.cancel(true); }
    }
}
