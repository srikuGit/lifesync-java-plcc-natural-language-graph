package com.lifesync.poc.product;

import java.util.concurrent.*;

/** Bound slow model work so repeated requests cannot fill all web threads or an unbounded queue. */
public final class AiTasks {
    private static final ExecutorService WORKERS=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
        new SynchronousQueue<>(),task->{Thread t=new Thread(task,"ui-ai");t.setDaemon(true);return t;});
    private AiTasks() {}
    private static int timeoutSeconds() {
        try {return Math.max(5,Math.min(60,Integer.parseInt(System.getenv().getOrDefault("AI_TIMEOUT_SECONDS","60"))));}
        catch(NumberFormatException ex){return 60;}
    }
    public static <T> T run(Callable<T> task) throws Exception {
        Future<T> future=WORKERS.submit(task);
        try { return future.get(timeoutSeconds(),TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw ex; }
        finally { if(!future.isDone()) future.cancel(true); }
    }
}
