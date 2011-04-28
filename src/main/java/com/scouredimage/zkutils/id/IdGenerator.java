package com.scouredimage.zkutils.id;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.hbase.util.Bytes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.scouredimage.zkutils.id.IdGeneratorException.Code;

public class IdGenerator implements Watcher {
    
    private final static Logger LOG = LoggerFactory.getLogger(IdGenerator.class);
    
    private static final String ROOT_NODE = "";

    private static final int MAX_RETRIES = 5;

    private final String connectString;
    private final int sessionTimeout;
    private final long batchSize;
    private final ConcurrentHashMap<String, AtomicLong> counters;
    
    private ZooKeeper zk;
    private volatile boolean connected = false;
    
    public IdGenerator(final String connnectString, final int sessionTimeout, final long batchSize) throws IOException {
        this.connectString = connnectString;
        this.sessionTimeout = sessionTimeout;
        this.batchSize = batchSize;
        this.counters = new ConcurrentHashMap<String, AtomicLong>();
    }
    
    private void connect() throws IOException {
        LOG.debug("Initializing connection (connect={}, timeout={})", connectString, sessionTimeout);
        zk = new ZooKeeper(connectString, sessionTimeout, this);
        synchronized (this) {
            try {
                LOG.debug("Waiting for connection establishment ...");
                long start = System.currentTimeMillis();
                wait();
                long timeTaken = System.currentTimeMillis() - start;
                LOG.info("Connected! Time taken = {}", timeTaken);
            } catch (InterruptedException e) {
                LOG.debug("Sleep interrupted", e);
            }
        }
    }
    
    private String getPath(final String counter) {
        return String.format("%s/%s", ROOT_NODE, counter);
    }
    
    public boolean init(final String counter) throws IdGeneratorException, IOException {
        final String path = getPath(counter);
        
        if (!connected) {
            connect();
        }
        
        try {
            final String actual = zk.create(path, 
                                            Bytes.toBytes(batchSize), 
                                            ZooDefs.Ids.OPEN_ACL_UNSAFE, 
                                            CreateMode.PERSISTENT);
            
            if (path.equals(actual)) {
                counters.putIfAbsent(path, new AtomicLong(1L));
                return true;
            }
            
            return false;
            
        } catch (KeeperException e) {
            Code code = Code.UNKNOWN;
            if (e.code() == KeeperException.Code.NODEEXISTS) {
                code = Code.COUNTER_EXISTS;
            } 
            throw new IdGeneratorException(code, e);
        } catch (InterruptedException e) {
            throw new IdGeneratorException(Code.TRANSACTION_INTERRUPTED, e);
        }
    }
    
    public long next(final String counter) throws IOException {
        return next(counter, MAX_RETRIES);
    }
    
    public long next(final String counter, final int retries) throws IOException, IdGeneratorException {
        final String path = getPath(counter);
        
        for (int attempt = 1; attempt <= retries; attempt++) {
            
            if (!connected) {
                connect();
            }
            
            final AtomicLong counterValue = counters.get(path);
            
            while (true) {
                final long next = counterValue.get();
                if (next % batchSize == 0) {
                    break;
                }
                if (counterValue.compareAndSet(next, next + 1)) {
                    return next;
                }
            }
            
            try {
                final Stat stat = new Stat();
                
                final long start = Bytes.toLong(zk.getData(path, false, stat));
                final long end = start + batchSize;
                
                zk.setData(path, Bytes.toBytes(end), stat.getVersion());
                
                if (counters.replace(path, counterValue, new AtomicLong(start + 1L))) {
                    return start;
                } else {
                    continue;
                }
                
            } catch (KeeperException e) {
                final Code code;
                switch (e.code()) {
                case BADVERSION:
                    LOG.debug("Version conflict!");
                    continue;
                case CONNECTIONLOSS:
                    continue;
                case NONODE:
                    code = Code.COUNTER_MISSING;
                    break;
                default:
                    code = Code.UNKNOWN;
                }
                throw new IdGeneratorException(code, e);
            } catch (InterruptedException e) {
                throw new IdGeneratorException(Code.TRANSACTION_INTERRUPTED, e);
            }
        }
        
        throw new IdGeneratorException(Code.RETRIES_EXHAUSTED, 
                                       String.format("Maximum attempts (%d) exhausted", retries));
    }

    public void process(final WatchedEvent event) {
        if (event.getType() == EventType.None) {
            switch (event.getState()) {
            case SyncConnected:
                connected = true;
                synchronized (this) {
                    notifyAll();
                }
                break;
            case Expired:
                connected = false;
                break;
            }
        }
    }
}
