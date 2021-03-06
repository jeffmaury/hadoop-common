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
package org.apache.hadoop.hdfs.server.namenode;

import junit.framework.TestCase;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Iterator;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.FSConstants.SafeModeAction;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.namenode.NNStorage.NameNodeFile;
import org.apache.hadoop.hdfs.DFSUtil.ErrorSimulator;
import org.apache.hadoop.hdfs.server.common.HdfsConstants.StartupOption;
import org.apache.hadoop.hdfs.server.common.Storage.StorageDirectory;
import org.apache.hadoop.hdfs.server.namenode.NNStorage.NameNodeDirType;
import org.apache.hadoop.hdfs.tools.DFSAdmin;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;

/**
 * This class tests the creation and validation of a checkpoint.
 */
public class TestCheckpoint extends TestCase {
  static final long seed = 0xDEADBEEFL;
  static final int blockSize = 4096;
  static final int fileSize = 8192;
  static final int numDatanodes = 3;
  short replication = 3;

  static void writeFile(FileSystem fileSys, Path name, int repl)
    throws IOException {
    FSDataOutputStream stm = fileSys.create(name, true,
                                            fileSys.getConf().getInt("io.file.buffer.size", 4096),
                                            (short)repl, (long)blockSize);
    byte[] buffer = new byte[TestCheckpoint.fileSize];
    Random rand = new Random(TestCheckpoint.seed);
    rand.nextBytes(buffer);
    stm.write(buffer);
    stm.close();
  }
  
  
  static void checkFile(FileSystem fileSys, Path name, int repl)
    throws IOException {
    assertTrue(fileSys.exists(name));
    int replication = fileSys.getFileStatus(name).getReplication();
    assertEquals("replication for " + name, repl, replication);
    //We should probably test for more of the file properties.    
  }
  
  static void cleanupFile(FileSystem fileSys, Path name)
    throws IOException {
    assertTrue(fileSys.exists(name));
    fileSys.delete(name, true);
    assertTrue(!fileSys.exists(name));
  }

  /**
   * put back the old namedir
   */
  private void resurrectNameDir(File namedir) 
    throws IOException {
    String parentdir = namedir.getParent();
    String name = namedir.getName();
    File oldname =  new File(parentdir, name + ".old");
    if (!oldname.renameTo(namedir)) {
      assertTrue(false);
    }
  }

  /**
   * remove one namedir
   */
  private void removeOneNameDir(File namedir) 
    throws IOException {
    String parentdir = namedir.getParent();
    String name = namedir.getName();
    File newname =  new File(parentdir, name + ".old");
    if (!namedir.renameTo(newname)) {
      assertTrue(false);
    }
  }

  /*
   * Verify that namenode does not startup if one namedir is bad.
   */
  private void testNamedirError(Configuration conf, Collection<URI> namedirs) 
    throws IOException {
    System.out.println("Starting testNamedirError");
    MiniDFSCluster cluster = null;

    if (namedirs.size() <= 1) {
      return;
    }
    
    //
    // Remove one namedir & Restart cluster. This should fail.
    //
    File first = new File(namedirs.iterator().next().getPath());
    removeOneNameDir(first);
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).format(false).build();
      cluster.shutdown();
      assertTrue(false);
    } catch (Throwable t) {
      // no nothing
    }
    resurrectNameDir(first); // put back namedir
  }

  /**
   * Tests EditLogFileOutputStream doesn't throw NullPointerException on being
   * closed twice.
   * See https://issues.apache.org/jira/browse/HDFS-2011
   */
  public void testEditLogFileOutputStreamCloses()
    throws IOException,NullPointerException {
    System.out.println("Testing EditLogFileOutputStream doesn't throw " +
                       "NullPointerException on being closed twice");
    File editLogStreamFile = null;
    try {
      editLogStreamFile = new File(System.getProperty("test.build.data","/tmp"),
                                   "editLogStream.dat");
      EditLogFileOutputStream editLogStream =
                             new EditLogFileOutputStream(editLogStreamFile, 0);
      editLogStream.close();
      //Closing an twice should not throw a NullPointerException
      editLogStream.close();
    } finally {
      if (editLogStreamFile != null)
        // Cleanup the editLogStream.dat file we created
          editLogStreamFile.delete();
    }
    System.out.println("Successfully tested EditLogFileOutputStream doesn't " +
           "throw NullPointerException on being closed twice");
  }

  /**
   * Checks that an IOException in NNStorage.setCheckpointTimeInStorage is handled
   * correctly (by removing the storage directory)
   * See https://issues.apache.org/jira/browse/HDFS-2011
   */
  public void testSetCheckpointTimeInStorageHandlesIOException() throws Exception {
    System.out.println("Check IOException handled correctly by setCheckpointTimeInStorage");
    NNStorage nnStorage = new NNStorage(new HdfsConfiguration());
    ArrayList<URI> fsImageDirs = new ArrayList<URI>();
    ArrayList<URI> editsDirs = new ArrayList<URI>();
    File filePath =
      new File(System.getProperty("test.build.data","/tmp"), "storageDirToCheck");
    assertTrue("Couldn't create directory storageDirToCheck",
               filePath.exists() || filePath.mkdirs());
    try {
      fsImageDirs.add(filePath.toURI());
      editsDirs.add(filePath.toURI());
      // Initialize NNStorage
      nnStorage.setStorageDirectories(fsImageDirs, editsDirs);
      assertTrue("List of storage directories didn't have storageDirToCheck.",
                 nnStorage.getEditsDirectories().iterator().next().
                 toString().indexOf("storageDirToCheck") != -1);
      assertTrue("List of removed storage directories wasn't empty",
                 nnStorage.getRemovedStorageDirs().isEmpty());
    } finally {
      // Delete storage directory to cause IOException in setCheckpointTimeInStorage
      assertTrue("Couldn't remove directory " + filePath.getAbsolutePath(),
                 filePath.delete());
    }
    // Just call setCheckpointTimeInStorage using any random number
    nnStorage.setCheckpointTimeInStorage(1);
    List<StorageDirectory> listRsd = nnStorage.getRemovedStorageDirs();
    assertTrue("Removed directory wasn't what was expected",
               listRsd.size() > 0 && listRsd.get(listRsd.size() - 1).getRoot().
               toString().indexOf("storageDirToCheck") != -1);
    System.out.println("Successfully checked IOException is handled correctly "
                       + "by setCheckpointTimeInStorage");
  }

  /*
   * Simulate namenode crashing after rolling edit log.
   */
  @SuppressWarnings("deprecation")
  private void testSecondaryNamenodeError1(Configuration conf)
    throws IOException {
    System.out.println("Starting testSecondaryNamenodeError 1");
    Path file1 = new Path("checkpointxx.dat");
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
                                               .numDataNodes(numDatanodes)
                                               .format(false).build();
    cluster.waitActive();
    FileSystem fileSys = cluster.getFileSystem();
    try {
      assertTrue(!fileSys.exists(file1));
      //
      // Make the checkpoint fail after rolling the edits log.
      //
      SecondaryNameNode secondary = startSecondaryNameNode(conf);
      ErrorSimulator.setErrorSimulation(0);

      try {
        secondary.doCheckpoint();  // this should fail
        assertTrue(false);
      } catch (IOException e) {
      }
      ErrorSimulator.clearErrorSimulation(0);
      secondary.shutdown();

      //
      // Create a new file
      //
      writeFile(fileSys, file1, replication);
      checkFile(fileSys, file1, replication);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    //
    // Restart cluster and verify that file exists.
    // Then take another checkpoint to verify that the 
    // namenode restart accounted for the rolled edit logs.
    //
    System.out.println("Starting testSecondaryNamenodeError 2");
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDatanodes)
                                              .format(false).build();
    cluster.waitActive();
    // Also check that the edits file is empty here
    // and that temporary checkpoint files are gone.
    FSImage image = cluster.getNameNode().getFSImage();
    for (Iterator<StorageDirectory> it = 
           image.getStorage().dirIterator(NameNodeDirType.IMAGE); it.hasNext();) {
      StorageDirectory sd = it.next();
      assertFalse(image.getStorage().getStorageFile(sd, NameNodeFile.IMAGE_NEW).exists());
    }
    for (Iterator<StorageDirectory> it = 
           image.getStorage().dirIterator(NameNodeDirType.EDITS); it.hasNext();) {
      StorageDirectory sd = it.next();
      assertFalse(image.getStorage().getEditNewFile(sd).exists());
      File edits = image.getStorage().getEditFile(sd);
      assertTrue(edits.exists()); // edits should exist and be empty
      long editsLen = edits.length();
      assertTrue(editsLen == Integer.SIZE/Byte.SIZE);
    }
    
    fileSys = cluster.getFileSystem();
    try {
      checkFile(fileSys, file1, replication);
      cleanupFile(fileSys, file1);
      SecondaryNameNode secondary = startSecondaryNameNode(conf);
      secondary.doCheckpoint();
      secondary.shutdown();
    } finally {
      fileSys.close();
      cluster.shutdown();
    }
  }

  /*
   * Simulate a namenode crash after uploading new image
   */
  @SuppressWarnings("deprecation")
  private void testSecondaryNamenodeError2(Configuration conf)
    throws IOException {
    System.out.println("Starting testSecondaryNamenodeError 21");
    Path file1 = new Path("checkpointyy.dat");
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
                                               .numDataNodes(numDatanodes)
                                               .format(false).build();
    cluster.waitActive();
    FileSystem fileSys = cluster.getFileSystem();
    try {
      assertTrue(!fileSys.exists(file1));
      //
      // Make the checkpoint fail after uploading the new fsimage.
      //
      SecondaryNameNode secondary = startSecondaryNameNode(conf);
      ErrorSimulator.setErrorSimulation(1);

      try {
        secondary.doCheckpoint();  // this should fail
        assertTrue(false);
      } catch (IOException e) {
      }
      ErrorSimulator.clearErrorSimulation(1);
      secondary.shutdown();

      //
      // Create a new file
      //
      writeFile(fileSys, file1, replication);
      checkFile(fileSys, file1, replication);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    //
    // Restart cluster and verify that file exists.
    // Then take another checkpoint to verify that the 
    // namenode restart accounted for the rolled edit logs.
    //
    System.out.println("Starting testSecondaryNamenodeError 22");
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDatanodes).format(false).build();
    cluster.waitActive();
    fileSys = cluster.getFileSystem();
    try {
      checkFile(fileSys, file1, replication);
      cleanupFile(fileSys, file1);
      SecondaryNameNode secondary = startSecondaryNameNode(conf);
      secondary.doCheckpoint();
      secondary.shutdown();
    } finally {
      fileSys.close();
      cluster.shutdown();
    }
  }

  /*
   * Simulate a secondary namenode crash after rolling the edit log.
   */
  @SuppressWarnings("deprecation")
  private void testSecondaryNamenodeError3(Configuration conf)
    throws IOException {
    System.out.println("Starting testSecondaryNamenodeError 31");
    Path file1 = new Path("checkpointzz.dat");
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
                                               .numDataNodes(numDatanodes)
                                               .format(false).build();

    cluster.waitActive();
    FileSystem fileSys = cluster.getFileSystem();
    try {
      assertTrue(!fileSys.exists(file1));
      //
      // Make the checkpoint fail after rolling the edit log.
      //
      SecondaryNameNode secondary = startSecondaryNameNode(conf);
      ErrorSimulator.setErrorSimulation(0);

      try {
        secondary.doCheckpoint();  // this should fail
        assertTrue(false);
      } catch (IOException e) {
      }
      ErrorSimulator.clearErrorSimulation(0);
      secondary.shutdown(); // secondary namenode crash!

      // start new instance of secondary and verify that 
      // a new rollEditLog suceedes inspite of the fact that 
      // edits.new already exists.
      //
      secondary = startSecondaryNameNode(conf);
      secondary.doCheckpoint();  // this should work correctly
      secondary.shutdown();

      //
      // Create a new file
      //
      writeFile(fileSys, file1, replication);
      checkFile(fileSys, file1, replication);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    //
    // Restart cluster and verify that file exists.
    // Then take another checkpoint to verify that the 
    // namenode restart accounted for the twice-rolled edit logs.
    //
    System.out.println("Starting testSecondaryNamenodeError 32");
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDatanodes).format(false).build();
    cluster.waitActive();
    fileSys = cluster.getFileSystem();
    try {
      checkFile(fileSys, file1, replication);
      cleanupFile(fileSys, file1);
      SecondaryNameNode secondary = startSecondaryNameNode(conf);
      secondary.doCheckpoint();
      secondary.shutdown();
    } finally {
      fileSys.close();
      cluster.shutdown();
    }
  }

  /**
   * Simulate a secondary node failure to transfer image
   * back to the name-node.
   * Used to truncate primary fsimage file.
   */
  @SuppressWarnings("deprecation")
  void testSecondaryFailsToReturnImage(Configuration conf)
    throws IOException {
    System.out.println("Starting testSecondaryFailsToReturnImage");
    Path file1 = new Path("checkpointRI.dat");
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
                                               .numDataNodes(numDatanodes)
                                               .format(false).build();
    cluster.waitActive();
    FileSystem fileSys = cluster.getFileSystem();
    FSImage image = cluster.getNameNode().getFSImage();
    try {
      assertTrue(!fileSys.exists(file1));
      StorageDirectory sd = null;
      for (Iterator<StorageDirectory> it = 
                image.getStorage().dirIterator(NameNodeDirType.IMAGE); it.hasNext();)
         sd = it.next();
      assertTrue(sd != null);
      long fsimageLength = image.getStorage().getStorageFile(sd, NameNodeFile.IMAGE).length();
      //
      // Make the checkpoint
      //
      SecondaryNameNode secondary = startSecondaryNameNode(conf);
      ErrorSimulator.setErrorSimulation(2);

      try {
        secondary.doCheckpoint();  // this should fail
        assertTrue(false);
      } catch (IOException e) {
        System.out.println("testSecondaryFailsToReturnImage: doCheckpoint() " +
            "failed predictably - " + e);
      }
      ErrorSimulator.clearErrorSimulation(2);

      // Verify that image file sizes did not change.
      for (Iterator<StorageDirectory> it = 
              image.getStorage().dirIterator(NameNodeDirType.IMAGE); it.hasNext();) {
        assertTrue(image.getStorage().getStorageFile(it.next(), 
                                NameNodeFile.IMAGE).length() == fsimageLength);
      }

      secondary.shutdown();
    } finally {
      fileSys.close();
      cluster.shutdown();
    }
  }

  /**
   * Simulate namenode failing to send the whole file
   * secondary namenode sometimes assumed it received all of it
   */
  @SuppressWarnings("deprecation")
  void testNameNodeImageSendFail(Configuration conf)
    throws IOException {
    System.out.println("Starting testNameNodeImageSendFail");
    Path file1 = new Path("checkpointww.dat");
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
                                               .numDataNodes(numDatanodes)
                                               .format(false).build();
    cluster.waitActive();
    FileSystem fileSys = cluster.getFileSystem();
    try {
      assertTrue(!fileSys.exists(file1));
      //
      // Make the checkpoint fail after rolling the edit log.
      //
      SecondaryNameNode secondary = startSecondaryNameNode(conf);
      ErrorSimulator.setErrorSimulation(3);

      try {
        secondary.doCheckpoint();  // this should fail
        fail("Did not get expected exception");
      } catch (IOException e) {
        // We only sent part of the image. Have to trigger this exception
        assertTrue(e.getMessage().contains("is not of the advertised size"));
      }
      ErrorSimulator.clearErrorSimulation(3);
      secondary.shutdown(); // secondary namenode crash!

      // start new instance of secondary and verify that 
      // a new rollEditLog suceedes inspite of the fact that 
      // edits.new already exists.
      //
      secondary = startSecondaryNameNode(conf);
      secondary.doCheckpoint();  // this should work correctly
      secondary.shutdown();

      //
      // Create a new file
      //
      writeFile(fileSys, file1, replication);
      checkFile(fileSys, file1, replication);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }
  }
  /**
   * Test different startup scenarios.
   * <p><ol>
   * <li> Start of primary name-node in secondary directory must succeed. 
   * <li> Start of secondary node when the primary is already running in 
   *      this directory must fail.
   * <li> Start of primary name-node if secondary node is already running in 
   *      this directory must fail.
   * <li> Start of two secondary nodes in the same directory must fail.
   * <li> Import of a checkpoint must fail if primary 
   * directory contains a valid image.
   * <li> Import of the secondary image directory must succeed if primary 
   * directory does not exist.
   * <li> Recover failed checkpoint for secondary node.
   * <li> Complete failed checkpoint for secondary node.
   * </ol>
   */
  @SuppressWarnings("deprecation")
  void testStartup(Configuration conf) throws IOException {
    System.out.println("Startup of the name-node in the checkpoint directory.");
    String primaryDirs = conf.get(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY);
    String primaryEditsDirs = conf.get(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY);
    String checkpointDirs = conf.get(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_DIR_KEY);
    String checkpointEditsDirs = conf.get(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_EDITS_DIR_KEY);
    NameNode nn = startNameNode(conf, checkpointDirs, checkpointEditsDirs,
                                 StartupOption.REGULAR);

    // Starting secondary node in the same directory as the primary
    System.out.println("Startup of secondary in the same dir as the primary.");
    SecondaryNameNode secondary = null;
    try {
      secondary = startSecondaryNameNode(conf);
      assertFalse(secondary.getFSImage().getStorage().isLockSupported(0));
      secondary.shutdown();
    } catch (IOException e) { // expected to fail
      assertTrue(secondary == null);
    }
    nn.stop(); nn = null;

    // Starting primary node in the same directory as the secondary
    System.out.println("Startup of primary in the same dir as the secondary.");
    // secondary won't start without primary
    nn = startNameNode(conf, primaryDirs, primaryEditsDirs,
                        StartupOption.REGULAR);
    boolean succeed = false;
    do {
      try {
        secondary = startSecondaryNameNode(conf);
        succeed = true;
      } catch(IOException ie) { // keep trying
        System.out.println("Try again: " + ie.getLocalizedMessage());
      }
    } while(!succeed);
    nn.stop(); nn = null;
    try {
      nn = startNameNode(conf, checkpointDirs, checkpointEditsDirs,
                          StartupOption.REGULAR);
      assertFalse(nn.getFSImage().getStorage().isLockSupported(0));
      nn.stop(); nn = null;
    } catch (IOException e) { // expected to fail
      assertTrue(nn == null);
    }

    // Try another secondary in the same directory
    System.out.println("Startup of two secondaries in the same dir.");
    // secondary won't start without primary
    nn = startNameNode(conf, primaryDirs, primaryEditsDirs,
                        StartupOption.REGULAR);
    SecondaryNameNode secondary2 = null;
    try {
      secondary2 = startSecondaryNameNode(conf);
      assertFalse(secondary2.getFSImage().getStorage().isLockSupported(0));
      secondary2.shutdown();
    } catch (IOException e) { // expected to fail
      assertTrue(secondary2 == null);
    }
    nn.stop(); nn = null;
    secondary.shutdown();

    // Import a checkpoint with existing primary image.
    System.out.println("Import a checkpoint with existing primary image.");
    try {
      nn = startNameNode(conf, primaryDirs, primaryEditsDirs,
                          StartupOption.IMPORT);
      assertTrue(false);
    } catch (IOException e) { // expected to fail
      assertTrue(nn == null);
    }
    
    // Remove current image and import a checkpoint.
    System.out.println("Import a checkpoint with existing primary image.");
    List<URI> nameDirs = (List<URI>)FSNamesystem.getNamespaceDirs(conf);
    List<URI> nameEditsDirs = (List<URI>)FSNamesystem.
                                  getNamespaceEditsDirs(conf);
    long fsimageLength = new File(new File(nameDirs.get(0).getPath(), "current"), 
                                        NameNodeFile.IMAGE.getName()).length();
    for(URI uri : nameDirs) {
      File dir = new File(uri.getPath());
      if(dir.exists())
        if(!(FileUtil.fullyDelete(dir)))
          throw new IOException("Cannot remove directory: " + dir);
      if (!dir.mkdirs())
        throw new IOException("Cannot create directory " + dir);
    }

    for(URI uri : nameEditsDirs) {
      File dir = new File(uri.getPath());
      if(dir.exists())
        if(!(FileUtil.fullyDelete(dir)))
          throw new IOException("Cannot remove directory: " + dir);
      if (!dir.mkdirs())
        throw new IOException("Cannot create directory " + dir);
    }
    
    nn = startNameNode(conf, primaryDirs, primaryEditsDirs,
                        StartupOption.IMPORT);
    // Verify that image file sizes did not change.
    FSImage image = nn.getFSImage();
    for (Iterator<StorageDirectory> it = 
            image.getStorage().dirIterator(NameNodeDirType.IMAGE); it.hasNext();) {
      assertTrue(image.getStorage().getStorageFile(it.next(), 
                          NameNodeFile.IMAGE).length() == fsimageLength);
    }
    nn.stop();

    // recover failed checkpoint
    nn = startNameNode(conf, primaryDirs, primaryEditsDirs,
                        StartupOption.REGULAR);
    Collection<URI> secondaryDirs = FSImage.getCheckpointDirs(conf, null);
    for(URI uri : secondaryDirs) {
      File dir = new File(uri.getPath());
      Storage.rename(new File(dir, "current"), 
                     new File(dir, "lastcheckpoint.tmp"));
    }
    secondary = startSecondaryNameNode(conf);
    secondary.shutdown();
    for(URI uri : secondaryDirs) {
      File dir = new File(uri.getPath());
      assertTrue(new File(dir, "current").exists()); 
      assertFalse(new File(dir, "lastcheckpoint.tmp").exists());
    }
    
    // complete failed checkpoint
    for(URI uri : secondaryDirs) {
      File dir = new File(uri.getPath());
      Storage.rename(new File(dir, "previous.checkpoint"), 
                     new File(dir, "lastcheckpoint.tmp"));
    }
    secondary = startSecondaryNameNode(conf);
    secondary.shutdown();
    for(URI uri : secondaryDirs) {
      File dir = new File(uri.getPath());
      assertTrue(new File(dir, "current").exists()); 
      assertTrue(new File(dir, "previous.checkpoint").exists()); 
      assertFalse(new File(dir, "lastcheckpoint.tmp").exists());
    }
    nn.stop(); nn = null;
    
    // Check that everything starts ok now.
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDatanodes).format(false).build();
    cluster.waitActive();
    cluster.shutdown();
  }

  NameNode startNameNode( Configuration conf,
                          String imageDirs,
                          String editsDirs,
                          StartupOption start) throws IOException {
    conf.set(DFSConfigKeys.FS_DEFAULT_NAME_KEY, "hdfs://localhost:0");
    conf.set(DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY, "0.0.0.0:0");  
    conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY, imageDirs);
    conf.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY, editsDirs);
    String[] args = new String[]{start.getName()};
    NameNode nn = NameNode.createNameNode(args, conf);
    assertTrue(nn.isInSafeMode());
    return nn;
  }

  // This deprecation suppress warning does not work due to known Java bug:
  // http://bugs.sun.com/view_bug.do?bug_id=6460147
  @SuppressWarnings("deprecation")
  SecondaryNameNode startSecondaryNameNode(Configuration conf
                                          ) throws IOException {
    conf.set(DFSConfigKeys.DFS_NAMENODE_SECONDARY_HTTP_ADDRESS_KEY, "0.0.0.0:0");
    return new SecondaryNameNode(conf);
  }

  /**
   * Tests checkpoint in HDFS.
   */
  @SuppressWarnings("deprecation")
  public void testCheckpoint() throws IOException {
    Path file1 = new Path("checkpoint.dat");
    Path file2 = new Path("checkpoint2.dat");
    Collection<URI> namedirs = null;

    Configuration conf = new HdfsConfiguration();
    conf.set(DFSConfigKeys.DFS_NAMENODE_SECONDARY_HTTP_ADDRESS_KEY, "0.0.0.0:0");
    replication = (short)conf.getInt(DFSConfigKeys.DFS_REPLICATION_KEY, 3);
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
                                               .numDataNodes(numDatanodes).build();
    cluster.waitActive();
    FileSystem fileSys = cluster.getFileSystem();

    try {
      //
      // verify that 'format' really blew away all pre-existing files
      //
      assertTrue(!fileSys.exists(file1));
      assertTrue(!fileSys.exists(file2));
      namedirs = cluster.getNameDirs(0);

      //
      // Create file1
      //
      writeFile(fileSys, file1, replication);
      checkFile(fileSys, file1, replication);

      //
      // Take a checkpoint
      //
      SecondaryNameNode secondary = startSecondaryNameNode(conf);
      ErrorSimulator.initializeErrorSimulationEvent(4);
      secondary.doCheckpoint();
      secondary.shutdown();
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    //
    // Restart cluster and verify that file1 still exist.
    //
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDatanodes).format(false).build();
    cluster.waitActive();
    fileSys = cluster.getFileSystem();
    Path tmpDir = new Path("/tmp_tmp");
    try {
      // check that file1 still exists
      checkFile(fileSys, file1, replication);
      cleanupFile(fileSys, file1);

      // create new file file2
      writeFile(fileSys, file2, replication);
      checkFile(fileSys, file2, replication);

      //
      // Take a checkpoint
      //
      SecondaryNameNode secondary = startSecondaryNameNode(conf);
      secondary.doCheckpoint();
      
      fileSys.delete(tmpDir, true);
      fileSys.mkdirs(tmpDir);
      secondary.doCheckpoint();
      
      secondary.shutdown();
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    //
    // Restart cluster and verify that file2 exists and
    // file1 does not exist.
    //
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDatanodes).format(false).build();
    cluster.waitActive();
    fileSys = cluster.getFileSystem();

    assertTrue(!fileSys.exists(file1));
    assertTrue(fileSys.exists(tmpDir));

    try {
      // verify that file2 exists
      checkFile(fileSys, file2, replication);
    } finally {
      fileSys.close();
      cluster.shutdown();
    }

    // file2 is left behind.

    testNameNodeImageSendFail(conf);
    testSecondaryNamenodeError1(conf);
    testSecondaryNamenodeError2(conf);
    testSecondaryNamenodeError3(conf);
    testNamedirError(conf, namedirs);
    testSecondaryFailsToReturnImage(conf);
    testStartup(conf);
  }

  /**
   * Tests save namepsace.
   */
  public void testSaveNamespace() throws IOException {
    MiniDFSCluster cluster = null;
    DistributedFileSystem fs = null;
    FileContext fc;
    try {
      Configuration conf = new HdfsConfiguration();
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDatanodes).format(false).build();
      cluster.waitActive();
      fs = (DistributedFileSystem)(cluster.getFileSystem());
      fc = FileContext.getFileContext(cluster.getURI(0));

      // Saving image without safe mode should fail
      DFSAdmin admin = new DFSAdmin(conf);
      String[] args = new String[]{"-saveNamespace"};
      try {
        admin.run(args);
      } catch(IOException eIO) {
        assertTrue(eIO.getLocalizedMessage().contains("Safe mode should be turned ON"));
      } catch(Exception e) {
        throw new IOException(e);
      }
      // create new file
      Path file = new Path("namespace.dat");
      writeFile(fs, file, replication);
      checkFile(fs, file, replication);

      // create new link
      Path symlink = new Path("file.link");
      fc.createSymlink(file, symlink, false);
      assertTrue(fc.getFileLinkStatus(symlink).isSymlink());

      // verify that the edits file is NOT empty
      Collection<URI> editsDirs = cluster.getNameEditsDirs(0);
      for(URI uri : editsDirs) {
        File ed = new File(uri.getPath());
        assertTrue(new File(ed, "current/edits").length() > Integer.SIZE/Byte.SIZE);
      }

      // Saving image in safe mode should succeed
      fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      try {
        admin.run(args);
      } catch(Exception e) {
        throw new IOException(e);
      }
      // verify that the edits file is empty
      for(URI uri : editsDirs) {
        File ed = new File(uri.getPath());
        assertTrue(new File(ed, "current/edits").length() == Integer.SIZE/Byte.SIZE);
      }

      // restart cluster and verify file exists
      cluster.shutdown();
      cluster = null;

      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDatanodes).format(false).build();
      cluster.waitActive();
      fs = (DistributedFileSystem)(cluster.getFileSystem());
      checkFile(fs, file, replication);
      fc = FileContext.getFileContext(cluster.getURI(0));
      assertTrue(fc.getFileLinkStatus(symlink).isSymlink());
    } finally {
      if(fs != null) fs.close();
      if(cluster!= null) cluster.shutdown();
    }
  }
  
  /* Test case to test CheckpointSignature */
  @SuppressWarnings("deprecation")
  public void testCheckpointSignature() throws IOException {

    MiniDFSCluster cluster = null;
    Configuration conf = new HdfsConfiguration();

    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDatanodes)
        .format(false).build();
    NameNode nn = cluster.getNameNode();

    SecondaryNameNode secondary = startSecondaryNameNode(conf);
    // prepare checkpoint image
    secondary.doCheckpoint();
    CheckpointSignature sig = nn.rollEditLog();
    // manipulate the CheckpointSignature fields
    sig.setBlockpoolID("somerandomebpid");
    sig.clusterID = "somerandomcid";
    try {
      sig.validateStorageInfo(nn.getFSImage()); // this should fail
      assertTrue("This test is expected to fail.", false);
    } catch (Exception ignored) {
    }

    secondary.shutdown();
    cluster.shutdown();
  }
  
  /**
   * Starts two namenodes and two secondary namenodes, verifies that secondary
   * namenodes are configured correctly to talk to their respective namenodes
   * and can do the checkpoint.
   * 
   * @throws IOException
   */
  @SuppressWarnings("deprecation")
  public void testMultipleSecondaryNamenodes() throws IOException {
    Configuration conf = new HdfsConfiguration();
    String nameserviceId1 = "ns1";
    String nameserviceId2 = "ns2";
    conf.set(DFSConfigKeys.DFS_FEDERATION_NAMESERVICES, nameserviceId1
        + "," + nameserviceId2);
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).numNameNodes(2)
        .nameNodePort(9928).build();
    Configuration snConf1 = new HdfsConfiguration(cluster.getConfiguration(0));
    Configuration snConf2 = new HdfsConfiguration(cluster.getConfiguration(1));
    InetSocketAddress nn1RpcAddress = cluster.getNameNode(0).rpcAddress;
    InetSocketAddress nn2RpcAddress = cluster.getNameNode(1).rpcAddress;
    String nn1 = nn1RpcAddress.getHostName() + ":" + nn1RpcAddress.getPort();
    String nn2 = nn2RpcAddress.getHostName() + ":" + nn2RpcAddress.getPort();

    // Set the Service Rpc address to empty to make sure the node specific
    // setting works
    snConf1.set(DFSConfigKeys.DFS_NAMENODE_SERVICE_RPC_ADDRESS_KEY, "");
    snConf2.set(DFSConfigKeys.DFS_NAMENODE_SERVICE_RPC_ADDRESS_KEY, "");

    // Set the nameserviceIds
    snConf1.set(DFSUtil.getNameServiceIdKey(
        DFSConfigKeys.DFS_NAMENODE_SERVICE_RPC_ADDRESS_KEY, nameserviceId1), nn1);
    snConf2.set(DFSUtil.getNameServiceIdKey(
        DFSConfigKeys.DFS_NAMENODE_SERVICE_RPC_ADDRESS_KEY, nameserviceId2), nn2);

    SecondaryNameNode secondary1 = startSecondaryNameNode(snConf1);
    SecondaryNameNode secondary2 = startSecondaryNameNode(snConf2);

    // make sure the two secondary namenodes are talking to correct namenodes.
    assertEquals(secondary1.getNameNodeAddress().getPort(), nn1RpcAddress.getPort());
    assertEquals(secondary2.getNameNodeAddress().getPort(), nn2RpcAddress.getPort());
    assertTrue(secondary1.getNameNodeAddress().getPort() != secondary2
        .getNameNodeAddress().getPort());

    // both should checkpoint.
    secondary1.doCheckpoint();
    secondary2.doCheckpoint();
    secondary1.shutdown();
    secondary2.shutdown();
    cluster.shutdown();
  }
  
  /**
   * Simulate a secondary node failure to transfer image
   * back to the name-node.
   * Used to truncate primary fsimage file.
   */
  @SuppressWarnings("deprecation")
  public void testSecondaryImageDownload(Configuration conf)
    throws IOException {
    System.out.println("Starting testSecondaryImageDownload");
    conf.set(DFSConfigKeys.DFS_NAMENODE_SECONDARY_HTTP_ADDRESS_KEY, "0.0.0.0:0");
    Path dir = new Path("/checkpoint");
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
                                               .numDataNodes(numDatanodes)
                                               .format(false).build();
    cluster.waitActive();
    FileSystem fileSys = cluster.getFileSystem();
    FSImage image = cluster.getNameNode().getFSImage();
    try {
      assertTrue(!fileSys.exists(dir));
      //
      // Make the checkpoint
      //
      SecondaryNameNode secondary = startSecondaryNameNode(conf);
      long fsimageLength = image.getStorage()
        .getStorageFile(image.getStorage().dirIterator(NameNodeDirType.IMAGE).next(),
                        NameNodeFile.IMAGE).length();
      assertFalse("Image is downloaded", secondary.doCheckpoint());

      // Verify that image file sizes did not change.
      for (Iterator<StorageDirectory> it = 
             image.getStorage().dirIterator(NameNodeDirType.IMAGE); it.hasNext();) {
        assertTrue("Image size does not change", image.getStorage().getStorageFile(it.next(), 
                                NameNodeFile.IMAGE).length() == fsimageLength);
      }

      // change namespace
      fileSys.mkdirs(dir);
      assertTrue("Image is not downloaded", secondary.doCheckpoint());

      for (Iterator<StorageDirectory> it = 
             image.getStorage().dirIterator(NameNodeDirType.IMAGE); it.hasNext();) {
        assertTrue("Image size increased", 
                   image.getStorage().getStorageFile(it.next(), 
                                                     NameNodeFile.IMAGE).length() > fsimageLength);
     }

      secondary.shutdown();
    } finally {
      fileSys.close();
      cluster.shutdown();
    }
  }
}
