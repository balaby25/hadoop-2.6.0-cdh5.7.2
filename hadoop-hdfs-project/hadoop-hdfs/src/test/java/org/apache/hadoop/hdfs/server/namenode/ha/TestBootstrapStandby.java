/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode.ha;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSNNTopology;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.server.namenode.CheckpointSignature;
import org.apache.hadoop.hdfs.server.namenode.FSImageTestUtil;
import org.apache.hadoop.hdfs.server.namenode.NNStorage;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.NameNodeAdapter;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.GenericTestUtils.LogCapturer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableList;

public class TestBootstrapStandby {
  private static final Log LOG = LogFactory.getLog(TestBootstrapStandby.class);
  
  private MiniDFSCluster cluster;
  private NameNode nn0;
  
  @Before
  public void setupCluster() throws IOException {
    Configuration conf = new Configuration();

    MiniDFSNNTopology topology = new MiniDFSNNTopology()
      .addNameservice(new MiniDFSNNTopology.NSConf("ns1")
        .addNN(new MiniDFSNNTopology.NNConf("nn1").setHttpPort(20001))
        .addNN(new MiniDFSNNTopology.NNConf("nn2").setHttpPort(20002)));
    
    cluster = new MiniDFSCluster.Builder(conf)
      .nnTopology(topology)
      .numDataNodes(0)
      .build();
    cluster.waitActive();
    
    nn0 = cluster.getNameNode(0);
    cluster.transitionToActive(0);
    cluster.shutdownNameNode(1);
  }
  
  @After
  public void shutdownCluster() {
    if (cluster != null) {
      cluster.shutdown();
    }
  }
  
  /**
   * Test for the base success case. The primary NN
   * hasn't made any checkpoints, and we copy the fsimage_0
   * file over and start up.
   */
  @Test
  public void testSuccessfulBaseCase() throws Exception {
    removeStandbyNameDirs();
    
    try {
      cluster.restartNameNode(1);
      fail("Did not throw");
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains(
          "storage directory does not exist or is not accessible",
          ioe);
    }
    int expectedCheckpointTxId = (int)NameNodeAdapter.getNamesystem(nn0)
        .getFSImage().getMostRecentCheckpointTxId();
    
    int rc = BootstrapStandby.run(
        new String[]{"-nonInteractive"},
        cluster.getConfiguration(1));
    assertEquals(0, rc);
    
    // Should have copied over the namespace from the active
    FSImageTestUtil.assertNNHasCheckpoints(cluster, 1,
        ImmutableList.of(expectedCheckpointTxId));
    FSImageTestUtil.assertNNFilesMatch(cluster);

    // We should now be able to start the standby successfully.
    cluster.restartNameNode(1);
  }
  
  /**
   * Test for downloading a checkpoint made at a later checkpoint
   * from the active.
   */
  @Test
  public void testDownloadingLaterCheckpoint() throws Exception {
    // Roll edit logs a few times to inflate txid
    nn0.getRpcServer().rollEditLog();
    nn0.getRpcServer().rollEditLog();
    // Make checkpoint
    NameNodeAdapter.enterSafeMode(nn0, false);
    NameNodeAdapter.saveNamespace(nn0);
    NameNodeAdapter.leaveSafeMode(nn0);
    long expectedCheckpointTxId = NameNodeAdapter.getNamesystem(nn0)
      .getFSImage().getMostRecentCheckpointTxId();
    assertEquals(6, expectedCheckpointTxId);

    int rc = BootstrapStandby.run(
        new String[]{"-force"},
        cluster.getConfiguration(1));
    assertEquals(0, rc);
    
    // Should have copied over the namespace from the active
    FSImageTestUtil.assertNNHasCheckpoints(cluster, 1,
        ImmutableList.of((int)expectedCheckpointTxId));
    FSImageTestUtil.assertNNFilesMatch(cluster);

    // We should now be able to start the standby successfully.
    cluster.restartNameNode(1);
  }

  /**
   * Test for the case where the shared edits dir doesn't have
   * all of the recent edit logs.
   */
  @Test
  public void testSharedEditsMissingLogs() throws Exception {
    removeStandbyNameDirs();
    
    CheckpointSignature sig = nn0.getRpcServer().rollEditLog();
    assertEquals(3, sig.getCurSegmentTxId());
    
    // Should have created edits_1-2 in shared edits dir
    URI editsUri = cluster.getSharedEditsDir(0, 1);
    File editsDir = new File(editsUri);
    File editsSegment = new File(new File(editsDir, "current"),
        NNStorage.getFinalizedEditsFileName(1, 2));
    GenericTestUtils.assertExists(editsSegment);

    // Delete the segment.
    assertTrue(editsSegment.delete());
    
    // Trying to bootstrap standby should now fail since the edit
    // logs aren't available in the shared dir.
    LogCapturer logs = GenericTestUtils.LogCapturer.captureLogs(
        LogFactory.getLog(BootstrapStandby.class));
    try {
      int rc = BootstrapStandby.run(
          new String[]{"-force"},
          cluster.getConfiguration(1));
      assertEquals(BootstrapStandby.ERR_CODE_LOGS_UNAVAILABLE, rc);
    } finally {
      logs.stopCapturing();
    }
    GenericTestUtils.assertMatches(logs.getOutput(),
        "FATAL.*Unable to read transaction ids 1-3 from the configured shared");
  }
  
  @Test
  public void testStandbyDirsAlreadyExist() throws Exception {
    // Should not pass since standby dirs exist, force not given
    int rc = BootstrapStandby.run(
        new String[]{"-nonInteractive"},
        cluster.getConfiguration(1));
    assertEquals(BootstrapStandby.ERR_CODE_ALREADY_FORMATTED, rc);

    // Should pass with -force
    rc = BootstrapStandby.run(
        new String[]{"-force"},
        cluster.getConfiguration(1));
    assertEquals(0, rc);
  }
  
  /**
   * Test that, even if the other node is not active, we are able
   * to bootstrap standby from it.
   */
  @Test(timeout=30000)
  public void testOtherNodeNotActive() throws Exception {
    cluster.transitionToStandby(0);
    int rc = BootstrapStandby.run(
        new String[]{"-force"},
        cluster.getConfiguration(1));
    assertEquals(0, rc);
  }

  /**
   * Test that bootstrapping standby NN is not limited by
   * {@link DFSConfigKeys#DFS_IMAGE_TRANSFER_RATE_KEY}, but is limited by
   * {@link DFSConfigKeys#DFS_IMAGE_TRANSFER_BOOTSTRAP_STANDBY_RATE_KEY}
   * created by HDFS-8808.
   */
  @Test(timeout=30000)
  public void testRateThrottling() throws Exception {
    cluster.getConfiguration(0).setLong(
        DFSConfigKeys.DFS_IMAGE_TRANSFER_RATE_KEY, 1);
    cluster.restartNameNode(0);
    cluster.waitActive();
    nn0 = cluster.getNameNode(0);
    cluster.transitionToActive(0);


    int timeOut = updatePrimaryNNAndGetTimeout();
    // A very low DFS_IMAGE_TRANSFER_RATE_KEY value won't affect bootstrapping
    final AtomicBoolean bootStrapped = new AtomicBoolean(false);
    new Thread(
        new Runnable() {
          @Override
          public void run() {
            try {
              testSuccessfulBaseCase();
              bootStrapped.set(true);
            } catch (Exception e) {
              fail(e.getMessage());
            }
          }
        }
    ).start();
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      public Boolean get() {
        return bootStrapped.get();
      }
    }, 50, timeOut);

    shutdownCluster();
    setupCluster();
    cluster.getConfiguration(0).setLong(
        DFSConfigKeys.DFS_IMAGE_TRANSFER_BOOTSTRAP_STANDBY_RATE_KEY, 1);
    cluster.restartNameNode(0);
    cluster.waitActive();
    nn0 = cluster.getNameNode(0);
    cluster.transitionToActive(0);
    // A very low DFS_IMAGE_TRANSFER_BOOTSTRAP_STANDBY_RATE_KEY value should
    // cause timeout
    timeOut = updatePrimaryNNAndGetTimeout();
    bootStrapped.set(false);
    new Thread(
        new Runnable() {
          @Override
          public void run() {
            try {
              testSuccessfulBaseCase();
              bootStrapped.set(true);
            } catch (Exception e) {
              LOG.info(e.getMessage());
            }
          }
        }
    ).start();
    try {
      GenericTestUtils.waitFor(new Supplier<Boolean>() {
        public Boolean get() {
          return bootStrapped.get();
        }
      }, 50, timeOut);
      fail("Did not timeout");
    } catch (TimeoutException e) {
      LOG.info("Encountered expected timeout.");
    }
  }

  /**
   * Add enough content to the primary NN's fsimage so that it's larger than
   * the IO transfer buffer size of bootstrapping. The return the correct
   * timeout duration.
   */
  private int updatePrimaryNNAndGetTimeout() throws IOException{
    // Any reasonable test machine should be able to transfer 1 byte per MS
    // (which is ~1K/s)
    final int minXferRatePerMS = 1;
    int imageXferBufferSize = HdfsConstants.IO_FILE_BUFFER_SIZE;
    File imageFile = null;
    int dirIdx = 0;
    while (imageFile == null || imageFile.length() < imageXferBufferSize) {
      for (int i = 0; i < 5; i++) {
        cluster.getFileSystem(0).mkdirs(new Path("/foo" + dirIdx++));
      }
      nn0.getRpcServer().rollEditLog();
      NameNodeAdapter.enterSafeMode(nn0, false);
      NameNodeAdapter.saveNamespace(nn0);
      NameNodeAdapter.leaveSafeMode(nn0);
      imageFile = FSImageTestUtil.findLatestImageFile(FSImageTestUtil
          .getFSImage(nn0).getStorage().getStorageDir(0));
    }

    return (int)(imageFile.length() / minXferRatePerMS) + 1;
  }

  private void removeStandbyNameDirs() {
    for (URI u : cluster.getNameDirs(1)) {
      assertTrue(u.getScheme().equals("file"));
      File dir = new File(u.getPath());
      LOG.info("Removing standby dir " + dir);
      assertTrue(FileUtil.fullyDelete(dir));
    }
  }
}
