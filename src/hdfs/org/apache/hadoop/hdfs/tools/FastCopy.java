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
package org.apache.hadoop.hdfs.tools;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.cli.Options;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.LeaseRenewal;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.ClientDatanodeProtocol;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlockWithMetaInfo;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.LocatedBlocksWithMetaInfo;
import org.apache.hadoop.hdfs.server.common.HdfsConstants;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.NotReplicatedYetException;
import org.apache.hadoop.ipc.ProtocolProxy;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.security.UnixUserGroupInformation;

/**
 * There is a need to perform fast file copy on HDFS (primarily for the purpose
 * of HBase Snapshot). The fast copy mechanism for a file works as follows :
 *
 * 1) Query metadata for all blocks of the source file.
 *
 * 2) For each block 'b' of the file, find out its datanode locations.
 *
 * 3) For each block of the file, add an empty block to the namesystem for the
 * destination file.
 *
 * 4) For each location of the block, instruct the datanode to make a local copy
 * of that block.
 *
 * 5) Once each datanode has copied over the its respective blocks, they report
 * to the namenode about it.
 *
 * 6) Wait for all blocks to be copied and exit.
 *
 * This would speed up the copying process considerably by removing top of the
 * rack data transfers.
 **/

public class FastCopy {

  private static final Log LOG = LogFactory.getLog(FastCopy.class);
  protected final Configuration conf;
  private final FileSystem srcFs;
  private final FileSystem dstFs;

  private final ClientProtocol srcNamenode;
  private final ClientProtocol dstNamenode;

  private ProtocolProxy<ClientProtocol> srcNamenodeProtocolProxy;
  private ProtocolProxy<ClientProtocol> dstNamenodeProtocolProxy;

  private final Random random = new Random();
  private String clientName;
  public static int THREAD_POOL_SIZE = 5;
  private final ExecutorService executor;
  // Map used to store the status of each block.
  private final Map<Block, BlockStatus> blockStatusMap =
    new ConcurrentHashMap<Block, BlockStatus>();
  // Map used to store the datanode errors.
  private final Map<DatanodeInfo, Integer> datanodeErrors =
    new ConcurrentHashMap<DatanodeInfo, Integer>();
  private final Map <String, ClientDatanodeProtocol> datanodeMap =
    new ConcurrentHashMap<String, ClientDatanodeProtocol>();
  // Maximum time to wait for a file copy to complete.
  public final long MAX_WAIT_TIME;
  public static final long BLK_WAIT_SLEEP_TIME = 5000; // 5 seconds
  // Map used to store the file status of each file being copied by this tool.
  private final Map<String, FastCopyFileStatus> fileStatusMap =
    new ConcurrentHashMap<String, FastCopyFileStatus>();
  private final int maxDatanodeErrors;
  private final LeaseChecker leaseChecker;
  private final short minReplication;

  public FastCopy() throws Exception {
    this(new Configuration());
  }

  public FastCopy(Configuration conf) throws Exception {
    this(conf, null, null, THREAD_POOL_SIZE);
  }

  public FastCopy(Configuration conf, DistributedFileSystem srcFs,
      DistributedFileSystem dstFs) throws Exception {
    this(conf, srcFs, dstFs, THREAD_POOL_SIZE);
  }

  public FastCopy(Configuration conf, DistributedFileSystem srcFs,
      DistributedFileSystem dstFs, int threadPoolSize) throws Exception {
    this.conf = conf;
    this.executor = Executors.newFixedThreadPool(threadPoolSize);
    this.clientName = "FastCopy" + random.nextInt();
    MAX_WAIT_TIME = conf.getInt("dfs.fastcopy.file.wait_time",
        30 * 60 * 1000); // 30 minutes default
    minReplication = (short) conf.getInt("dfs.replication.min", 1);

    if (srcFs == null) {
      this.srcFs = FileSystem.get(conf);
    } else {
      this.srcFs = srcFs;
    }

    if (dstFs == null) {
      this.dstFs = FileSystem.get(conf);
    } else {
      this.dstFs = dstFs;
    }

    this.srcNamenodeProtocolProxy = DFSClient.createRPCNamenode(
        NameNode.getAddress(this.srcFs.getUri().getAuthority()), conf,
        UnixUserGroupInformation.login(conf, true));
    this.srcNamenode = this.srcNamenodeProtocolProxy.getProxy();
    // If both FS are same don't create unnecessary extra RPC connection.
    if (this.dstFs.getUri().compareTo(this.srcFs.getUri()) != 0) {
      this.dstNamenodeProtocolProxy = DFSClient.createRPCNamenode(
          NameNode.getAddress(this.dstFs.getUri().getAuthority()), conf,
          UnixUserGroupInformation.login(conf, true));
      this.dstNamenode = this.dstNamenodeProtocolProxy.getProxy();
    } else {
      this.dstNamenodeProtocolProxy = this.srcNamenodeProtocolProxy;
      this.dstNamenode = this.srcNamenode;
    }
    this.maxDatanodeErrors = conf.getInt("dfs.fastcopy.max.datanode.errors", 5);
    this.leaseChecker = new LeaseChecker();
    new Thread(leaseChecker).start();
  }

  /**
   * Retrieves the {@link FastCopyFileStatus} object for the file.
   * 
   * @param file
   *          the file whose status we need
   * @return returns the {@link FastCopyFileStatus} for the specified file or
   *         null if no status exists for the file yet.
   */
  public FastCopyFileStatus getFileStatus(String file) {
    return fileStatusMap.get(file);
  }

  private class FastFileCopy implements Callable<Boolean> {
    private final String src;
    private final String destination;
    private final ExecutorService blockRPCExecutor;
    private IOException blkRpcException = null;
    public final int blockRPCExecutorPoolSize;
    private int totalBlocks;

    /**
     * The main purpose of this class it to issue block copy RPC requests to the
     * datanode in a sync manner. Each thread copies a single replica of the
     * block.
     */
    private class BlockCopyRPC implements Runnable {
      private final Block src;
      private final Block dst;
      private final int srcNamespaceId, dstNamespaceId;
      private final boolean supportFederation;
      private final DatanodeInfo srcDn;
      private final DatanodeInfo dstDn;

      public BlockCopyRPC(int srcNamespaceId, Block src, int dstNamespaceId,
          Block dst, boolean supportFederation, DatanodeInfo srcDn,
          DatanodeInfo dstDn) {
        this.src = src;
        this.dst = dst;
        this.srcNamespaceId = srcNamespaceId;
        this.dstNamespaceId = dstNamespaceId;
        this.supportFederation = supportFederation;
        this.srcDn = srcDn;
        this.dstDn = dstDn;
      }

      public void run() {
        int srcErrors = (datanodeErrors.get(srcDn) == null) ? 0
          : datanodeErrors
          .get(srcDn);
        int dstErrors = (datanodeErrors.get(dstDn) == null) ? 0
          : datanodeErrors
          .get(dstDn);
        if (srcErrors > maxDatanodeErrors || dstErrors > maxDatanodeErrors) {
          LOG.warn((srcErrors > maxDatanodeErrors) ? srcDn : dstDn
              + " is bad, aborting the copy of block " + src.getBlockName()
              + " to " + dst.getBlockName() + " from datanode " + srcDn
              + " to datanode " + dstDn);
          return;
        }
        copyBlockReplica();
      }

      /**
       * Creates an RPC connection to a datanode if connection not already
       * cached and caches the connection if a new RPC connection is created
       * 
       * @param dn
       *          the datanode to which we need to connect to
       * @param conf
       *          the configuration for this RPC
       * @param timeout
       *          the RPC timeout for this connection
       * @return the RPC protocol object we can use to make RPC calls
       * @throws IOException
       */
      private ClientDatanodeProtocol getDatanodeConnection(DatanodeInfo dn,
          Configuration conf, int timeout) throws IOException {
        // This is done to improve read performance, no need for
        // synchronization on the map when we do a read. We go through this
        // method for each block.
        ClientDatanodeProtocol cdp = datanodeMap.get(dn.getName());
        if (cdp != null) {
          return cdp;
        }
        synchronized (datanodeMap) {
          cdp = datanodeMap.get(dn.getName());
          if (cdp == null) {
            LOG.debug("Creating new RPC connection to : " + dn.getName());
            cdp = DFSClient.createClientDatanodeProtocolProxy(
                dn, conf, timeout);
            datanodeMap.put(dn.getName(), cdp);
          }
        }
        return cdp;
      }

      /**
       * Increments the number of errors for a datanode in the datanodeErrors
       * map by 1.
       * 
       * @param node
       *          the datanode which needs to be update
       */
      private void updateDatanodeErrors(DatanodeInfo node) {
        synchronized (datanodeErrors) {
          Integer errors = datanodeErrors.get(node);
          if (errors == null) {
            errors = new Integer(0);
          }
          int e = errors;
          datanodeErrors.put(node, new Integer(++e));
        }
      }

      /**
       * Adds a datanode to excludesNodes if it has too many errors.
       * 
       * @param e
       *          the Exception thrown while copying a block
       */
      private void handleException(Exception e) {
        // If we have a remote exception there was an error on the destination
        // datanode, otherwise we have an error on the source datanode to which
        // we made the RPC connection.
        if (RemoteException.class.isInstance(e)) {
          updateDatanodeErrors(dstDn);
        } else {
          updateDatanodeErrors(srcDn);
        }
      }

      /**
       * Updates the file status by incrementing the total number of blocks done
       * for this file by 1.
       */
      private void updateFileStatus() {
        synchronized (fileStatusMap) {
          FastCopyFileStatus fcpStat = fileStatusMap.get(destination);
          if (fcpStat == null) {
            fcpStat = new FastCopyFileStatus(destination, totalBlocks);
            fileStatusMap.put(destination, fcpStat);
          }
          fcpStat.addBlock();
        }
      }

      /**
       * Updates the status of a block. If the block is in the
       * {@link FastCopy#blockStatusMap} we are still waiting for the block to
       * reach the desired replication level.
       * 
       * @param b
       *          the block whose status needs to be updated
       * @param isError
       *          whether or not the block had an error
       */
      private void updateBlockStatus(Block b, boolean isError) {
        synchronized (blockStatusMap) {
          BlockStatus bStatus = blockStatusMap.get(b);
          if (bStatus == null) {
            return;
          }
          if (isError) {
            bStatus.addBadReplica();
            if (bStatus.isBadBlock()) {
              blockStatusMap.remove(b);
              blkRpcException = new IOException(
                  "All replicas are bad for block : " + b.getBlockName());
            }
          } else {
            bStatus.addGoodReplica();
            // We are removing the block from the blockStatusMap, this indicates
            // that the block has reached the desired replication so now we
            // update the fileStatusMap. Note that this will happen only once
            // for each block.
            if (bStatus.isGoodBlock()) {
              blockStatusMap.remove(b);
              updateFileStatus();
            }
          }
        }
      }

      /**
       * Copies over a single replica of a block to a destination datanode.
       */
      private void copyBlockReplica() {
        boolean error = false;
        try {
          // Timeout of 8 minutes for this RPC, this is sufficient since
          // PendingReplicationMonitor timeout itself is 5 minutes.
          ClientDatanodeProtocol cdp = getDatanodeConnection(srcDn, conf,
              HdfsConstants.WRITE_TIMEOUT);
          LOG.debug("Fast Copy : Copying block " + src.getBlockName() + " to "
              + dst.getBlockName() + " on " + dstDn.getHostName());
          // This is a blocking call that does not return until the block is
          // successfully copied on the Datanode.
          if (supportFederation) {
            cdp.copyBlock(srcNamespaceId, src, 
                dstNamespaceId, dst, dstDn,
                false);
          } else {
            cdp.copyBlock(src, dst, dstDn,
                false);
          }
        } catch (Exception e) {
          String errMsg = "Fast Copy : Failed for Copying block "
            + src.getBlockName() + " to " + dst.getBlockName() + " on "
            + dstDn.getHostName();
          LOG.warn(errMsg, e);
          error = true;
          handleException(e);
        }
        updateBlockStatus(dst, error);
      }
    }

    public FastFileCopy(String src, String destination) throws Exception {
      // Remove the hdfs:// prefix and only use path component.
      this.src = new URI(src).getRawPath();
      this.destination = new URI(destination).getRawPath();
      // This controls the number of concurrent blocks that would be copied per
      // file. So if we are concurrently copying 5 files at a time by setting
      // THREAD_POOL_SIZE to 5 and allowing 5 concurrent file copies and this
      // value is set at 5, we would have 25 blocks in all being copied by the
      // tool in parallel.
      this.blockRPCExecutorPoolSize = conf.getInt(
          "dfs.fastcopy.blockRPC.pool_size", 5);
      this.blockRPCExecutor = Executors
        .newFixedThreadPool(this.blockRPCExecutorPoolSize);
    }

    public Boolean call() throws Exception {
      return copy();
    }
    
    private void checkAndThrowException() throws IOException {
      if (blkRpcException != null) {
        throw blkRpcException;
      }
    }

    /**
     * Copies all the replicas for a single block
     * 
     * @param src
     *          the source block
     * @param dst
     *          the destination block
     * @param srcNamespaceId 
     *          namespace id of the source block
     * @param dstNamespaceId 
     *          namespace id of the destination block
     * @param supportFederation 
     *          whether or not federation is supported.
     * @throws Exception
     */
    private void copyBlock(LocatedBlock src, LocatedBlock dst,
        int srcNamespaceId, int dstNamespaceId, boolean supportFederation)
      throws Exception {
      // Sorting source and destination locations so that we don't rely at all
      // on the ordering of the locations that we receive from the NameNode.
      DatanodeInfo[] dstLocs = dst.getLocations();
      DatanodeInfo[] srcLocs = src.getLocations();
      Arrays.sort(dstLocs);
      Arrays.sort(srcLocs);

      // We use minimum here, since its better for the NameNode to handle the
      // extra locations in either list. The locations that match up are the
      // ones we have chosen in our tool so we handle copies for only those.
      short blocksToCopy = (short) Math.min(srcLocs.length, dstLocs.length);
      Block srcBlock = src.getBlock();
      Block dstBlock = dst.getBlock();
      initializeBlockStatus(dstBlock, blocksToCopy);
      for (int i = 0; i < blocksToCopy; i++) {
        blockRPCExecutor.submit(new BlockCopyRPC(srcNamespaceId, 
              srcBlock, dstNamespaceId, dstBlock, supportFederation, srcLocs[i],
              dstLocs[i]));
      }
    }

    /**
     * Waits for the blocks of the file to be completed to a particular
     * threshold.
     * 
     * @param blocksAdded
     *          the number of blocks already added to the namenode
     * @throws IOException
     */
    private void waitForBlockCopy(int blocksAdded)
      throws IOException {
      while (true) {
        FastCopyFileStatus fcpStatus = fileStatusMap.get(destination);
        // If the datanodes are not lagging or this is the first block that will
        // be added to the namenode, no need to wait longer.
        int blocksDone = fcpStatus == null ? 0 : fcpStatus.getBlocksDone();
        if (blocksAdded == blocksDone || blocksAdded == 0) {
          break;
        }
        if (blkRpcException != null) {
          throw blkRpcException;
        }
        sleepFor(1000);
      }
    }

    /**
     * Initializes the block status map with information about a block.
     * 
     * @param b
     *          the block to be added to the {@link FastCopy#blockStatusMap}
     * @param totalReplicas
     *          the number of replicas for b
     */
    private void initializeBlockStatus(Block b, short totalReplicas) {
      BlockStatus bStatus = new BlockStatus(totalReplicas);
      blockStatusMap.put(b, bStatus);
    }

    /**
     * Shuts down the block rpc executor.
     */
    private void terminateExecutor() throws IOException {
      blockRPCExecutor.shutdown();
      try {
        blockRPCExecutor.awaitTermination(MAX_WAIT_TIME, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        throw new InterruptedIOException(e.getMessage());
      }
    }

    /**
     * Sleeps the current thread for <code>ms</code> milliseconds.
     * 
     * @param ms
     *          the number of milliseconds to sleep the current thread
     * @throws IOException
     *           if it encountered an InterruptedException
     */
    private void sleepFor(long ms) throws IOException {
      try {
        Thread.sleep(ms);
      } catch (InterruptedException e) {
        throw new IOException(e.getMessage());
      }
    }

    /**
     * Retrieves a new block for the file {@link FastFileCopy#destination} from
     * the NameNode.
     * 
     * @param supportFederation
     *          whether or not federation is supported
     * @param favoredNodes
     *          the favored nodes on which we want to place this block
     * @param startPos
     *          the starting position of this block in the file
     * @return the newly allocated block from the NameNode
     * @throws IOException
     *           if it could not allocated a block
     */
    private LocatedBlock getBlockFromNameNode(boolean supportFederation,
        DatanodeInfo[] favoredNodes, long startPos) throws IOException {
      LocatedBlock destinationLocatedBlock = null;
      int maxRetries = 10;
      int retries = 0;
      while (true) {
        try {
          if (dstNamenodeProtocolProxy.isMethodSupported(
              "addBlockAndFetchMetaInfo", String.class, String.class,
              DatanodeInfo[].class, DatanodeInfo[].class, boolean.class,
              long.class)) {
            if (!supportFederation) {
              throw new IOException(
                  "Fastcopy is not allowed from "
                      + "a non-federeated HDFS cluster to a federated HDFS cluster!");
            }
            LocatedBlockWithMetaInfo dstLocatedBlockWithMetaInfo = dstNamenode
                .addBlockAndFetchMetaInfo(destination, clientName, null,
                    favoredNodes, startPos);
            destinationLocatedBlock = dstLocatedBlockWithMetaInfo;
          } else if (dstNamenodeProtocolProxy.isMethodSupported(
              "addBlockAndFetchMetaInfo", String.class, String.class,
              DatanodeInfo[].class, DatanodeInfo[].class, boolean.class)) {
            if (!supportFederation) {
              throw new IOException(
                  "Fastcopy is not allowed from "
                      + "a non-federeated HDFS cluster to a federated HDFS cluster!");
            }
            LocatedBlockWithMetaInfo dstLocatedBlockWithMetaInfo = dstNamenode
                .addBlockAndFetchMetaInfo(destination, clientName, null,
                    favoredNodes);
            destinationLocatedBlock = dstLocatedBlockWithMetaInfo;
          } else {
            if (supportFederation) {
              throw new IOException(
                  "Fastcopy is not allowed from "
                      + "a federeated HDFS cluster to a non-federated HDFS cluster!");
            }
            destinationLocatedBlock = dstNamenode.addBlock(destination,
                clientName, null, favoredNodes);
          }
        } catch (RemoteException re) {
          if (re.unwrapRemoteException() instanceof NotReplicatedYetException) {
            if (retries > maxRetries) {
              throw re;
            }
            sleepFor(1000);
            retries++;
            continue;
          }
        }
        break;
      }
      return destinationLocatedBlock;
    }

    private boolean copy() throws Exception {
      // Get source file information and create empty destination file.
      FileStatus srcFileStatus = srcNamenode.getFileInfo(src);
      if (srcFileStatus == null) {
        throw new FileNotFoundException("File : " + src + " does not exist");
      }
      dstNamenode.create(destination, srcFileStatus.getPermission(),
          clientName, true, true, srcFileStatus.getReplication(),
          srcFileStatus.getBlockSize());
      try {
        LocatedBlocks srcLocatedBlks;
        int srcNamespaceId = 0;
        boolean supportFederation = false;
        if (srcNamenodeProtocolProxy.isMethodSupported(
            "openAndFetchMetaInfo", String.class, long.class, long.class)) {
          supportFederation = true;
          LocatedBlocksWithMetaInfo srcBlockWithMetaInfo = 
            srcNamenode.openAndFetchMetaInfo(src, 0, Long.MAX_VALUE);
          srcNamespaceId = srcBlockWithMetaInfo.getNamespaceID();
          srcLocatedBlks = srcBlockWithMetaInfo;
        } else {
          srcLocatedBlks = srcNamenode.getBlockLocations(src, 0, Long.MAX_VALUE);
        }
        List<LocatedBlock> locatedBlocks = srcLocatedBlks.getLocatedBlocks();
        this.totalBlocks = locatedBlocks.size();
        
        LOG.debug("FastCopy : Block locations retrieved for : " + src);
        
        // Instruct each datanode to create a copy of the respective block.
        int blocksAdded = 0;
        long startPos = 0;
        // Loop through each block and create copies.
        for (LocatedBlock srcLocatedBlock : locatedBlocks) {
          DatanodeInfo[] favoredNodes = srcLocatedBlock.getLocations();

          LocatedBlock destinationLocatedBlock = getBlockFromNameNode(
              supportFederation, favoredNodes, startPos);
          int dstNamespaceId = 0;
          if (destinationLocatedBlock instanceof LocatedBlockWithMetaInfo) {
            dstNamespaceId = ((LocatedBlockWithMetaInfo) destinationLocatedBlock)
                .getNamespaceID();
          }
          blocksAdded++;

          startPos += srcLocatedBlock.getBlockSize();

          LOG.debug("Fast Copy : Block "
              + destinationLocatedBlock.getBlock()
              + " added to namenode");
          
         copyBlock(srcLocatedBlock, destinationLocatedBlock, srcNamespaceId,
             dstNamespaceId, supportFederation);

          // Wait for the block copies to reach a threshold.
          waitForBlockCopy(blocksAdded);

          checkAndThrowException();

        }

        terminateExecutor();

        // Wait for all blocks of the file to be copied.
        waitForFile(src, destination);
      } catch (IOException e) {
        LOG.error("failed to copy src : " + src + " dst : " + destination, e);
        // If we fail to copy, cleanup destination.
        dstNamenode.delete(destination, false);
        throw e;
      }
      return true;
    }

    /**
     * Waits for all blocks of the file to be copied over.
     *
     * @param src
     *          the source file
     * @param destination
     *          the destination file
     * @throws IOException
     */
    private void waitForFile(String src, String destination)
        throws IOException {
      boolean flag = dstNamenode.complete(destination, clientName);
      long startTime = System.currentTimeMillis();

      while (!flag) {
        checkAndThrowException();
        LOG.debug("Fast Copy : Waiting for all blocks of file " + destination
            + " to be replicated");
        sleepFor(BLK_WAIT_SLEEP_TIME);
        if (System.currentTimeMillis() - startTime > MAX_WAIT_TIME) {
          throw new IOException("Fast Copy : Could not complete file copy, "
              + "timedout while waiting for blocks to be copied");
        }
        flag = dstNamenode.complete(destination, clientName);
      }
      LOG.debug("Fast Copy succeeded for files src : " + src + " destination "
          + destination);
    }
  }

  /**
   * Tears down all RPC connections, you MUST call this once you are done.
   * @throws IOException
   */
  public void shutdown() throws IOException {
    // Clean up RPC connections.
    RPC.stopProxy(srcNamenode);
    RPC.stopProxy(dstNamenode);
    Iterator <ClientDatanodeProtocol> connections =
      datanodeMap.values().iterator();
    while(connections.hasNext()) {
      ClientDatanodeProtocol cnxn = connections.next();
      RPC.stopProxy(cnxn);
    }
    leaseChecker.closeRenewal();
    datanodeMap.clear();
  }

  /**
   * Performs a fast copy of the src file to the destination file. This method
   * tries to copy the blocks of the source file on the same local machine and
   * then stitch up the blocks to build the new destination file
   * 
   * @param src
   *          the source file, this should be the full HDFS URI
   * 
   * @param destination
   *          the destination file, this should be the full HDFS URI
   */
  public void copy(String src, String destination)
      throws Exception {
    Callable<Boolean> fastFileCopy = new FastFileCopy(src, destination);
    Future<Boolean> f = executor.submit(fastFileCopy);
    f.get();
  }

  /**
   * Performs fast copy for a list of fast file copy requests. Uses a thread
   * pool to perform fast file copy in parallel.
   *
   * @param requests
   *          the list of fast file copy requests
   * @throws Exception
   */
  public void copy(List<FastFileCopyRequest> requests) throws Exception {
    List<Future<Boolean>> results = new ArrayList<Future<Boolean>>();

    for (FastFileCopyRequest r : requests) {
      Callable<Boolean> fastFileCopy = new FastFileCopy(r.getSrc(),
          r.getDestination());
      Future<Boolean> f = executor.submit(fastFileCopy);
      results.add(f);
    }

    for (Future<Boolean> f : results) {
      f.get();
    }
  }

  /**
   * Renews the lease for the files that are being copied.
   */
  public class LeaseChecker extends LeaseRenewal {

    public LeaseChecker() {
      super(clientName, conf);
    }

    @Override
    protected void renew() throws IOException {
      dstNamenode.renewLease(clientName);
    }

    @Override
    protected void abort() {
      // Do nothing.
    }
  }

  /**
   * Stores the status of a single block, the number of replicas that are bad
   * and the total number of expected replicas.
   */
  public class BlockStatus {
    private final short totalReplicas;
    private short badReplicas;
    private short goodReplicas;

    public BlockStatus(short totalReplicas) {
      this.totalReplicas = totalReplicas;
      this.badReplicas = 0;
      this.goodReplicas = 0;
    }

    public void addBadReplica() {
      this.badReplicas++;
    }

    public boolean isBadBlock() {
      return (badReplicas >= totalReplicas);
    }

    public void addGoodReplica() {
      this.goodReplicas++;
    }

    public boolean isGoodBlock() {
      return (this.goodReplicas >= minReplication);
    }
  }

  /**
   * This is used for status reporting by the Fast Copy tool
   */
  public static class FastCopyFileStatus {
    // Total number of blocks to be copied.
    private final int totalBlocks;
    // The file that data is being copied to.
    private final String file;
    // The total number of blocks done till now.
    private int blocksDone;

    public FastCopyFileStatus(String file, int totalBlocks) {
      this.totalBlocks = totalBlocks;
      this.file = file;
      this.blocksDone = 0;
    }

    public String getFileName() {
      return this.file;
    }

    public int getTotalBlocks() {
      return this.totalBlocks;
    }

    public int getBlocksDone() {
      return this.blocksDone;
    }

    public void addBlock() {
      this.blocksDone++;
    }
  }

  public static class FastFileCopyRequest {

    private final String src;
    private final String dst;

    public FastFileCopyRequest(String src, String dst) {
      this.src = src;
      this.dst = dst;
    }

    /**
     * @return the src
     */
    public String getSrc() {
      return src;
    }

    /**
     * @return the destination
     */
    public String getDestination() {
      return dst;
    }
  }

  /**
   * Wrapper class that holds the source and destination path for a file to be
   * copied. This is to help in easy computation of source and destination files
   * while copying directories.
   */
  private static class CopyPath {
    private final Path srcPath;
    private final Path dstPath;

    /**
     * @param srcPath
     *          source path from where the file should be copied from.
     * @param dstPath
     *          destination path where the file should be copied to
     */
    public CopyPath(Path srcPath, Path dstPath) {
      this.srcPath = srcPath;
      this.dstPath = dstPath;
    }

    /**
     * @return the srcPath
     */
    public Path getSrcPath() {
      return srcPath;
    }

    /**
     * @return the dstPath
     */
    public Path getDstPath() {
      return dstPath;
    }

  }

  private static Options options = new Options();
  private static Configuration defaultConf = new Configuration();

  private static void printUsage() {
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp("Usage : FastCopy [options] <srcs....> <dst>", options);
  }
  
  private static CommandLine parseCommandline(String args[])
      throws ParseException {
    options.addOption("t", "threads", true, "The number of concurrent theads" +
        " to use, one thread per file");

    CommandLineParser parser = new PosixParser();
    CommandLine cmd = parser.parse(options, args);

    return cmd;
  }

  /**
   * Recursively lists out all the files under a given path.
   * 
   * @param root
   *          the path under which we want to list out files
   * @param fs
   *          the filesystem
   * @param result
   *          the list which holds all the files.
   * @throws IOException
   */
  private static void getDirectoryListing(FileStatus root, FileSystem fs,
      List<CopyPath> result, Path dstPath) throws IOException {
    if (!root.isDir()) {
      result.add(new CopyPath(root.getPath(), dstPath));
      return;
    }

    for (FileStatus child : fs.listStatus(root.getPath())) {
      getDirectoryListing(child, fs, result, new Path(dstPath, child.getPath()
          .getName()));
    }
  }

  /**
   * Get the listing of all files under the given directories.
   * 
   * @param fs
   *          the filesystem
   * @param paths
   *          the paths whose directory listing is to be retrieved
   * @return the directory expansion for all paths provided
   * @throws IOException
   */
  private static List<CopyPath> expandDirectories(FileSystem fs,
      List<Path> paths, Path dstPath)
      throws IOException {
    List<CopyPath> newList = new ArrayList<CopyPath>();
    FileSystem dstFs = dstPath.getFileSystem(defaultConf);

    boolean isDstFile = false;
    try {
      FileStatus dstPathStatus = dstFs.getFileStatus(dstPath);
      if (!dstPathStatus.isDir()) {
        isDstFile = true;
      }
    } catch (FileNotFoundException e) {
      isDstFile = true;
    }

    for (Path path : paths) {
      FileStatus pathStatus = fs.getFileStatus(path);
      if (!pathStatus.isDir()) {
        // This is the case where the destination is a file, in this case, we
        // allow only a single source file. This check has been done below in
        // FastCopy#parseFiles(List, String[])
        if (isDstFile) {
          newList.add(new CopyPath(path, dstPath));
        } else {
          newList.add(new CopyPath(path, new Path(dstPath, path.getName())));
        }
      } else {
        // If we are copying /a/b/c into /x/y/z and 'z' does not exist, we
        // create the structure /x/y/z/f*, where f* represents all files and
        // directories in c/
        Path rootPath = dstPath;
        // This ensures if we copy a directory like /a/b/c to a directory
        // /x/y/z/, we will create the directory structure /x/y/z/c, if 'z'
        // exists.
        if (dstFs.exists(dstPath)) {
          rootPath = new Path(dstPath, pathStatus.getPath().getName());
        }
        getDirectoryListing(pathStatus, fs, newList, rootPath);
      }
    }
    return newList;
  }

  /**
   * Expand a single file, if its a file pattern list out all files matching the
   * pattern, if its a directory return all files under the directory.
   * 
   * @param src
   *          the file to be expanded
   * @param dstPath
   *          the destination
   * @return the expanded file list for this file/filepattern
   * @throws IOException
   */
  private static List<CopyPath> expandSingle(Path src, Path dstPath)
      throws IOException {
    List<Path> expandedPaths = new ArrayList<Path>();
    FileSystem fs = src.getFileSystem(defaultConf);
    FileStatus[] stats = fs.globStatus(src);
    if (stats == null || stats.length == 0) {
      throw new IOException("Path : " + src + " is invalid");
    }
    for (FileStatus stat : stats) {
      expandedPaths.add(stat.getPath());
    }
    List<CopyPath> expandedDirs = expandDirectories(fs, expandedPaths, dstPath);
    return expandedDirs;
  }

  /**
   * Expands all sources, if they are file pattern expand to list out all files
   * matching the pattern, if they are a directory, expand to list out all files
   * under the directory.
   * 
   * @param srcs
   *          the files to be expanded
   * @param dstPath
   *          the destination
   * @return the fully expanded list of all files for all file/filepatterns
   *         provided.
   * @throws IOException
   */
  private static List<CopyPath> expandSrcs(List<Path> srcs, Path dstPath)
      throws IOException {
    List<CopyPath> expandedSrcs = new ArrayList<CopyPath>();
    for (Path src : srcs) {
      expandedSrcs.addAll(expandSingle(src, dstPath));
    }
    return expandedSrcs;
  }

  private static String parseFiles(List<CopyPath> expandedSrcs, String args[])
      throws IOException {
    if (args.length < 2) {
      printUsage();
      System.exit(1);
    }

    List<Path> srcs = new ArrayList<Path>();
    for (int i = 0; i < args.length - 1; i++) {
      srcs.add(new Path(args[i]));
    }

    String dst = args[args.length - 1];
    Path dstPath = new Path(dst);
    expandedSrcs.clear();
    expandedSrcs.addAll(expandSrcs(srcs, dstPath));

    FileSystem dstFs = dstPath.getFileSystem(defaultConf);

    // If we have multiple source files, the destination has to be a directory.
    if (dstFs.exists(dstPath) && !dstFs.getFileStatus(dstPath).isDir()
        && expandedSrcs.size() > 1) {
      printUsage();
      throw new IllegalArgumentException("Path : " + dstPath
          + " is not a directory");
    }

    // If the expected destination is a directory and it does not exist throw
    // an error.
    if (!dstFs.exists(dstPath) && srcs.size() > 1) {
      printUsage();
      throw new IllegalArgumentException("Path : " + dstPath
          + " does not exist");
    }

    return dst;
  }


  public static void runTool(String args[]) throws Exception {
    CommandLine cmd = parseCommandline(args);
    args = cmd.getArgs();
    int threadPoolSize = (cmd.hasOption('t')) ? Integer.parseInt(cmd
        .getOptionValue('t')) : THREAD_POOL_SIZE;
    
    List<CopyPath> srcs = new ArrayList<CopyPath>();
    String dst = parseFiles(srcs, args);

    Path dstPath = new Path(dst);
    DistributedFileSystem dstFileSys = (DistributedFileSystem) dstPath
        .getFileSystem(defaultConf);


    DistributedFileSystem srcFileSys = (DistributedFileSystem) srcs.get(0)
        .getSrcPath().getFileSystem(defaultConf);
    List<FastFileCopyRequest> requests = new ArrayList<FastFileCopyRequest>();
    FastCopy fcp = new FastCopy(new Configuration(), srcFileSys, dstFileSys,
        threadPoolSize);

    try {
      for (CopyPath copyPath : srcs) {
        Path srcPath = copyPath.getSrcPath();
        String src = srcPath.toString();
        try {
          // Perform some error checking and path manipulation.

          if (!srcFileSys.exists(srcPath)) {
            throw new IOException("File : " + src + " does not exists on "
                + srcFileSys);
          }

          String destination = copyPath.getDstPath().toString();
          LOG.debug("Copying : " + src + " to " + destination);
          requests.add(new FastFileCopyRequest(src, destination));
        } catch (Exception e) {
          LOG.warn("Fast Copy failed for file : " + src, e);
        }
      }
      fcp.copy(requests);
      LOG.debug("Finished copying");
    } finally {
      fcp.shutdown();
    }
  }

  public static void main(String args[]) throws Exception {
    runTool(args);
    System.exit(0);
  }
}
