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
package org.apache.hadoop.hdfs;

import static org.apache.hadoop.hdfs.BlockStoragePolicy.ID_UNSPECIFIED;

import java.io.FileNotFoundException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.DirectoryListing;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.server.namenode.snapshot.SnapshotTestHelper;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.Assert;
import org.junit.Test;

/** Test {@link BlockStoragePolicy} */
public class TestBlockStoragePolicy {
  public static final BlockStoragePolicy.Suite POLICY_SUITE;
  public static final BlockStoragePolicy DEFAULT_STORAGE_POLICY;
  public static final Configuration conf;

  static {
    conf = new HdfsConfiguration();
    POLICY_SUITE = BlockStoragePolicy.readBlockStorageSuite(conf);
    DEFAULT_STORAGE_POLICY = POLICY_SUITE.getDefaultPolicy();
  }

  static final EnumSet<StorageType> none = EnumSet.noneOf(StorageType.class);
  static final EnumSet<StorageType> archive = EnumSet.of(StorageType.ARCHIVE);
  static final EnumSet<StorageType> disk = EnumSet.of(StorageType.DISK);
  static final EnumSet<StorageType> both = EnumSet.of(StorageType.DISK, StorageType.ARCHIVE);

  static final long FILE_LEN = 1024;
  static final short REPLICATION = 3;

  static final byte COLD = (byte) 4;
  static final byte WARM = (byte) 8;
  static final byte HOT  = (byte) 12;

  @Test
  public void testDefaultPolicies() throws Exception {
    final Map<Byte, String> expectedPolicyStrings = new HashMap<Byte, String>();
    expectedPolicyStrings.put(COLD,
        "BlockStoragePolicy{COLD:4, storageTypes=[ARCHIVE], creationFallbacks=[], replicationFallbacks=[]");
    expectedPolicyStrings.put(WARM,
        "BlockStoragePolicy{WARM:8, storageTypes=[DISK, ARCHIVE], creationFallbacks=[DISK, ARCHIVE], replicationFallbacks=[DISK, ARCHIVE]");
    expectedPolicyStrings.put(HOT,
        "BlockStoragePolicy{HOT:12, storageTypes=[DISK], creationFallbacks=[], replicationFallbacks=[ARCHIVE]");

    for(byte i = 1; i < 16; i++) {
      final BlockStoragePolicy policy = POLICY_SUITE.getPolicy(i); 
      if (policy != null) {
        final String s = policy.toString();
        Assert.assertEquals(expectedPolicyStrings.get(i), s);
      }
    }
    Assert.assertEquals(POLICY_SUITE.getPolicy(HOT), POLICY_SUITE.getDefaultPolicy());
    
    { // check Cold policy
      final BlockStoragePolicy cold = POLICY_SUITE.getPolicy(COLD);
      for(short replication = 1; replication < 6; replication++) {
        final List<StorageType> computed = cold.chooseStorageTypes(replication);
        assertStorageType(computed, replication, StorageType.ARCHIVE);
      }
      assertCreationFallback(cold, null, null, null);
      assertReplicationFallback(cold, null, null, null);
    }
    
    { // check Warm policy
      final BlockStoragePolicy warm = POLICY_SUITE.getPolicy(WARM);
      for(short replication = 1; replication < 6; replication++) {
        final List<StorageType> computed = warm.chooseStorageTypes(replication);
        assertStorageType(computed, replication, StorageType.DISK, StorageType.ARCHIVE);
      }
      assertCreationFallback(warm, StorageType.DISK, StorageType.DISK, StorageType.ARCHIVE);
      assertReplicationFallback(warm, StorageType.DISK, StorageType.DISK, StorageType.ARCHIVE);
    }

    { // check Hot policy
      final BlockStoragePolicy hot = POLICY_SUITE.getPolicy(HOT);
      for(short replication = 1; replication < 6; replication++) {
        final List<StorageType> computed = hot.chooseStorageTypes(replication);
        assertStorageType(computed, replication, StorageType.DISK);
      }
      assertCreationFallback(hot, null, null, null);
      assertReplicationFallback(hot, StorageType.ARCHIVE, null, StorageType.ARCHIVE);
    }
  }

  static void assertStorageType(List<StorageType> computed, short replication,
      StorageType... answers) {
    Assert.assertEquals(replication, computed.size());
    final StorageType last = answers[answers.length - 1];
    for(int i = 0; i < computed.size(); i++) {
      final StorageType expected = i < answers.length? answers[i]: last;
      Assert.assertEquals(expected, computed.get(i));
    }
  }

  static void assertCreationFallback(BlockStoragePolicy policy, StorageType noneExpected,
      StorageType archiveExpected, StorageType diskExpected) {
    Assert.assertEquals(noneExpected, policy.getCreationFallback(none));
    Assert.assertEquals(archiveExpected, policy.getCreationFallback(archive));
    Assert.assertEquals(diskExpected, policy.getCreationFallback(disk));
    Assert.assertEquals(null, policy.getCreationFallback(both));
  }

  static void assertReplicationFallback(BlockStoragePolicy policy, StorageType noneExpected,
      StorageType archiveExpected, StorageType diskExpected) {
    Assert.assertEquals(noneExpected, policy.getReplicationFallback(none));
    Assert.assertEquals(archiveExpected, policy.getReplicationFallback(archive));
    Assert.assertEquals(diskExpected, policy.getReplicationFallback(disk));
    Assert.assertEquals(null, policy.getReplicationFallback(both));
  }

  private void checkDirectoryListing(HdfsFileStatus[] stats, byte... policies) {
    Assert.assertEquals(stats.length, policies.length);
    for (int i = 0; i < stats.length; i++) {
      Assert.assertEquals(stats[i].getStoragePolicy(), policies[i]);
    }
  }

  @Test
  public void testSetStoragePolicy() throws Exception {
    final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(REPLICATION).build();
    cluster.waitActive();
    final DistributedFileSystem fs = cluster.getFileSystem();
    try {
      final Path dir = new Path("/testSetStoragePolicy");
      final Path fooFile = new Path(dir, "foo");
      final Path barDir = new Path(dir, "bar");
      final Path barFile1= new Path(barDir, "f1");
      final Path barFile2= new Path(barDir, "f2");
      DFSTestUtil.createFile(fs, fooFile, FILE_LEN, REPLICATION, 0L);
      DFSTestUtil.createFile(fs, barFile1, FILE_LEN, REPLICATION, 0L);
      DFSTestUtil.createFile(fs, barFile2, FILE_LEN, REPLICATION, 0L);

      final String invalidPolicyName = "INVALID-POLICY";
      try {
        fs.setStoragePolicy(fooFile, invalidPolicyName);
        Assert.fail("Should throw a HadoopIllegalArgumentException");
      } catch (RemoteException e) {
        GenericTestUtils.assertExceptionContains(invalidPolicyName, e);
      }

      // check storage policy
      HdfsFileStatus[] dirList = fs.getClient().listPaths(dir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      HdfsFileStatus[] barList = fs.getClient().listPaths(barDir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(dirList, ID_UNSPECIFIED, ID_UNSPECIFIED);
      checkDirectoryListing(barList, ID_UNSPECIFIED, ID_UNSPECIFIED);

      final Path invalidPath = new Path("/invalidPath");
      try {
        fs.setStoragePolicy(invalidPath, "WARM");
        Assert.fail("Should throw a FileNotFoundException");
      } catch (FileNotFoundException e) {
        GenericTestUtils.assertExceptionContains(invalidPath.toString(), e);
      }

      fs.setStoragePolicy(fooFile, "COLD");
      fs.setStoragePolicy(barDir, "WARM");
      fs.setStoragePolicy(barFile2, "HOT");

      dirList = fs.getClient().listPaths(dir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      barList = fs.getClient().listPaths(barDir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      checkDirectoryListing(dirList, WARM, COLD); // bar is warm, foo is cold
      checkDirectoryListing(barList, WARM, HOT);

      // restart namenode to make sure the editlog is correct
      cluster.restartNameNode(true);
      dirList = fs.getClient().listPaths(dir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      barList = fs.getClient().listPaths(barDir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(dirList, WARM, COLD); // bar is warm, foo is cold
      checkDirectoryListing(barList, WARM, HOT);

      // restart namenode with checkpoint to make sure the fsimage is correct
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      fs.saveNamespace();
      fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);
      cluster.restartNameNode(true);
      dirList = fs.getClient().listPaths(dir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      barList = fs.getClient().listPaths(barDir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      checkDirectoryListing(dirList, WARM, COLD); // bar is warm, foo is cold
      checkDirectoryListing(barList, WARM, HOT);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  @Test
  public void testSetStoragePolicyWithSnapshot() throws Exception {
    final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(REPLICATION).build();
    cluster.waitActive();
    final DistributedFileSystem fs = cluster.getFileSystem();
    try {
      final Path dir = new Path("/testSetStoragePolicyWithSnapshot");
      final Path fooDir = new Path(dir, "foo");
      final Path fooFile1= new Path(fooDir, "f1");
      final Path fooFile2= new Path(fooDir, "f2");
      DFSTestUtil.createFile(fs, fooFile1, FILE_LEN, REPLICATION, 0L);
      DFSTestUtil.createFile(fs, fooFile2, FILE_LEN, REPLICATION, 0L);

      fs.setStoragePolicy(fooDir, "WARM");

      HdfsFileStatus[] dirList = fs.getClient().listPaths(dir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(dirList, WARM);
      HdfsFileStatus[] fooList = fs.getClient().listPaths(fooDir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(fooList, WARM, WARM);

      // take snapshot
      SnapshotTestHelper.createSnapshot(fs, dir, "s1");
      // change the storage policy of fooFile1
      fs.setStoragePolicy(fooFile1, "COLD");

      fooList = fs.getClient().listPaths(fooDir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      checkDirectoryListing(fooList, COLD, WARM);

      // check the policy for /dir/.snapshot/s1/foo/f1
      Path s1f1 = SnapshotTestHelper.getSnapshotPath(dir, "s1", "foo/f1");
      DirectoryListing f1Listing = fs.getClient().listPaths(s1f1.toString(),
          HdfsFileStatus.EMPTY_NAME);
      checkDirectoryListing(f1Listing.getPartialListing(), WARM);

      // delete f1
      fs.delete(fooFile1, true);
      fooList = fs.getClient().listPaths(fooDir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      checkDirectoryListing(fooList, WARM);
      // check the policy for /dir/.snapshot/s1/foo/f1 again after the deletion
      checkDirectoryListing(fs.getClient().listPaths(s1f1.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing(), WARM);

      // change the storage policy of foo dir
      fs.setStoragePolicy(fooDir, "HOT");
      // /dir/foo is now hot
      dirList = fs.getClient().listPaths(dir.toString(),
          HdfsFileStatus.EMPTY_NAME, true).getPartialListing();
      checkDirectoryListing(dirList, HOT);
      // /dir/foo/f2 is hot
      fooList = fs.getClient().listPaths(fooDir.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing();
      checkDirectoryListing(fooList, HOT);

      // check storage policy of snapshot path
      Path s1 = SnapshotTestHelper.getSnapshotRoot(dir, "s1");
      Path s1foo = SnapshotTestHelper.getSnapshotPath(dir, "s1", "foo");
      checkDirectoryListing(fs.getClient().listPaths(s1.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing(), WARM);
      // /dir/.snapshot/.s1/foo/f1 and /dir/.snapshot/.s1/foo/f2 are warm 
      checkDirectoryListing(fs.getClient().listPaths(s1foo.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing(), WARM, WARM);

      // delete foo
      fs.delete(fooDir, true);
      checkDirectoryListing(fs.getClient().listPaths(s1.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing(), WARM); 
      checkDirectoryListing(fs.getClient().listPaths(s1foo.toString(),
          HdfsFileStatus.EMPTY_NAME).getPartialListing(), WARM, WARM);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }
}