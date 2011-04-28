package com.scouredimage.zkutils.id;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import junit.framework.Assert;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.MiniZooKeeperCluster;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.scouredimage.zkutils.id.IdGeneratorException.Code;

public class IdGeneratorTest {
    
    public static final String BASE_TEST_DIR = "test/build/data";
    
    private static final int BATCH_SIZE = 3;
    
    private static MiniZooKeeperCluster cluster;
    private static int clientPort;
    
    private static IdGenerator generator;

    @BeforeClass
    public static void setUp() throws IOException, InterruptedException {
        cluster = new MiniZooKeeperCluster();
        
        final File testDir = new File(getUnitTestDir(IdGeneratorTest.class.getName()));
        clientPort = cluster.startup(testDir);
        
        final String connectString = String.format("%s:%d", "0.0.0.0", clientPort);
        generator = new IdGenerator(connectString, Integer.MAX_VALUE, BATCH_SIZE);
    }
    
    private static String getUnitTestDir(final String name) {
        return new Path(BASE_TEST_DIR, name).toString();
    }

    @AfterClass
    public static void tearDown() throws IOException {
        cluster.shutdown();
    }
    
    @Test
    public void testOverflowBatch() throws IOException {
        final String testCounter = "test1";

        Assert.assertTrue(generator.init(testCounter));
        
        for (int count = 1; count <= BATCH_SIZE + 1; count++) {
            Assert.assertEquals(count, generator.next(testCounter));
        }
    }
    
    @Test
    public void testInitExisting() throws IOException {
        final String testCounter = "test2";
        
        // Must pass!
        Assert.assertTrue(generator.init(testCounter));
        
        // Must fail!
        try {
            generator.init(testCounter);
        } catch (IdGeneratorException e) {
            if (e.getCode() != Code.COUNTER_EXISTS) {
                throw e;
            }
        }
    }
    
    @Test
    public void testSequence() throws Exception {
        final String testCounter = "test3";
        
        Assert.assertTrue(generator.init(testCounter));
        
        final ExecutorService service = Executors.newCachedThreadPool();
        
        final List<Future<?>> futures = new ArrayList<Future<?>>();
        final ConcurrentLinkedQueue<Long> results = new ConcurrentLinkedQueue<Long>();
        
        for (int i = 0 ; i < BATCH_SIZE * 2; i++) {
            futures.add(service.submit(new Runnable() {
                                           public void run() {
                                               try {
                                                   results.add(generator.next(testCounter));
                                               } catch (Exception e) {
                                                   throw new RuntimeException(e);
                                               }
                                           }
                                       }));
        }
        
        for (Future<?> future : futures) {
            future.get();
        }
        service.shutdownNow();
        
        Assert.assertEquals(BATCH_SIZE * 2, results.size());
        
        long last = 0;
        for (Long result : results) {
            Assert.assertTrue(last < result);
        }
    }
    
}
