package com.cloudera.iterativereduce.yarn.appworker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.avro.AvroRemoteException;
import org.apache.avro.ipc.NettyTransceiver;
import org.apache.avro.ipc.specific.SpecificRequestor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;

import com.cloudera.iterativereduce.ComputableWorker;
import com.cloudera.iterativereduce.Updateable;
import com.cloudera.iterativereduce.Utils;
import com.cloudera.iterativereduce.io.RecordParser;
import com.cloudera.iterativereduce.yarn.avro.generated.IterativeReduceService;
import com.cloudera.iterativereduce.yarn.avro.generated.ProgressReport;
import com.cloudera.iterativereduce.yarn.avro.generated.ServiceError;
import com.cloudera.iterativereduce.yarn.avro.generated.StartupConfiguration;
import com.cloudera.iterativereduce.yarn.avro.generated.WorkerId;

public class ApplicationWorkerService<T extends Updateable> {

  private static final Log LOG = LogFactory
      .getLog(ApplicationWorkerService.class);

  private enum WorkerState {
    NONE, STARTED, RUNNING, WAITING, UPDATE
  }

  private WorkerId workerId;
  private InetSocketAddress masterAddr;
  private WorkerState currentState;
  private NettyTransceiver nettyTransceiver;
  private IterativeReduceService masterService;

  private StartupConfiguration workerConf;

  private RecordParser<T> recordParser;
  private ComputableWorker<T> computable;
  private Class<T> updateable;

  private Map<String, Integer> progressCounters;
  private ProgressReport progressReport;

  private long statusSleepTime = 2000L;
  private long updateSleepTime = 1000L;
  
  private ExecutorService updateExecutor;
  private Configuration conf;
  
  // Metrics
  private long mWorkerTime;
  private long mWorkerExecutions;
  private long mWaits;
  private long mWaitTime;
  private long mUpdates;

  class PeridoicUpdateThread implements Runnable {
    @Override
    public void run() {
      Thread.currentThread().setName("Periodic worker heartbeat thread");
      
      while (true) {
        LOG.debug("Attemping to acquire state lock");
        synchronized (currentState) {
          if (WorkerState.RUNNING == currentState) {
            LOG.debug("Worker is running, sending a progress report");
            try {
              masterService.progress(workerId, createProgressReport());
            } catch (AvroRemoteException ex) {
              LOG.warn("Encountered an exception while heartbeating to master",
                  ex);
            }
          }
        }
  
        try {
          LOG.debug("Thread " + Thread.currentThread().getName()
              + " is going to sleep for " + statusSleepTime);
          Thread.sleep(statusSleepTime);
        } catch (InterruptedException ex) {
          LOG.warn("Interrupted while sleeping on progress report");
          return;
        }
      }
    }
  }

  public ApplicationWorkerService(String wid, InetSocketAddress masterAddr,
      RecordParser<T> parser, ComputableWorker<T> computable,
      Class<T> updateable, Configuration conf) {

    this.workerId = Utils.createWorkerId(wid);
    this.currentState = WorkerState.NONE;
    this.masterAddr = masterAddr;
    this.recordParser = parser;
    this.computable = computable;
    this.updateable = updateable;
    this.progressCounters = new HashMap<String, Integer>();
    
    this.conf = conf;
  }
  
  public ApplicationWorkerService(String wid, InetSocketAddress masterAddr,
      RecordParser<T> parser, ComputableWorker<T> computable,
      Class<T> updateable) {

    this(wid, masterAddr, parser, computable, updateable, new Configuration());
  }

  /**
   * Main worker loop that feeds ComputableWorker records
   * - switched from sending a list of records at once to
   * - letting the end user control their own batches
   */
  public int run() {
    Thread.currentThread().setName(
        "ApplicationWorkerService Thread - " + Utils.getWorkerId(workerId));
    
    if (!initializeService())
      return -1;

    LOG.info("Worker " + Utils.getWorkerId(workerId) + " initialized");
    
    recordParser.setFile(workerConf.getSplit().getPath().toString(), workerConf.getSplit()
        .getOffset(), workerConf.getSplit().getLength());
    recordParser.parse();

    // Create an updater thread
    LOG.debug("Launching periodic update thread");
    updateExecutor = Executors.newSingleThreadExecutor();
    updateExecutor.execute(new PeridoicUpdateThread());

    // Do some work
    currentState = WorkerState.STARTED;
    //LinkedList<T> records = new LinkedList<T>();

    int countTotal = 0;
    int countCurrent = 0;
    int currentIteration = 0;

    computable.setRecordParser(recordParser);
    
    for (currentIteration = 0; currentIteration < workerConf.getIterations(); currentIteration++) {
    //while (doIterations) {  
      LOG.debug("Beginning iteration " + (currentIteration +1) + "/" + workerConf.getIterations());
      
      synchronized (currentState) {
        currentState = WorkerState.RUNNING;
      }
        
        recordParser.reset();
        int lastUpdate = 0;
        
  
  
          countTotal++;
          countCurrent++;
  
          synchronized (progressCounters) {
            progressCounters.put("countTotal", countTotal);
            progressCounters.put("countCurrent", countCurrent);
            progressCounters.put("currentIteration", currentIteration);
          }
  
          
          /**
           * Run the compute side, let the user handle their own batch
           * 
           */
          
          long mWorkerStart = System.currentTimeMillis();
          T workerUpdate = computable.compute();
          
          mWorkerExecutions++;
          mWorkerTime += (System.currentTimeMillis() - mWorkerStart);
          
          /**
           * send update to master from this worker
           */
          try {
              synchronized (currentState) {
                ByteBuffer bytes = workerUpdate.toBytes(); 
                bytes.rewind();
                
                LOG.info("Sending an update to master");
                currentState = WorkerState.UPDATE;
                if (!masterService.update(workerId, bytes))
                  LOG.warn("The master rejected our update");
                
                mUpdates++;
              }
            } catch (AvroRemoteException ex) {
              LOG.error("Unable to send update message to master", ex);
              return -1;
            }
  
            // Wait on master for an update
            int nextUpdate;
  
            try {
              LOG.info("Completed a batch, waiting on an update from master");
              nextUpdate = waitOnMasterUpdate(lastUpdate);
              
            } catch (InterruptedException ex) {
              LOG.warn("Interrupted while waiting on master", ex);
              return -1;
            } catch (AvroRemoteException ex) {
              LOG.error("Got an error while waiting on updates from master", ex);
              return -1;
            }
  
            // Time to get an update
            try {
              ByteBuffer b = masterService.fetch(workerId, nextUpdate);
              b.rewind();
              T masterUpdate = updateable.newInstance();
              masterUpdate.fromBytes(b);
              computable.update(masterUpdate);
              lastUpdate = nextUpdate;
  
              LOG.debug("Requested to fetch an update from master"
                  + ", workerId=" + Utils.getWorkerId(workerId)
                  + ", requestedUpdatedId=" + nextUpdate
                  + ", responseLength=" + b.limit());
              
            } catch (AvroRemoteException ex) {
              LOG.error("Got exception while fetching an update from master", ex);
              return -1;
            } catch (Exception ex) {
              LOG.error("Got exception while processing update from master", ex);
              return -1;
            }
  
            countCurrent = 0;
        
        computable.IncrementIteration();
        
        
    } // while

    // Send a metrics report
    reportMetrics();
    
    // Send final update to master
    T finalUpdate = computable.getResults();
    if (finalUpdate != null) {
      try {
        LOG.info("Sending final update to master");
        masterService.update(workerId, finalUpdate.toBytes());
      } catch (AvroRemoteException ex) {
        LOG.warn("Failed to send final update to master", ex);
      }
    }
    
    // We're done
    LOG.info("Completed processing, notfiying master that we're done");
    masterService.complete(workerId, createProgressReport());
    // BUG: because master does not track connection<->worker, if the complete
    // message does not arrive, master will be in a hung state awaiting
    // worker completion. :(
    // Temp workaround: sleep for 1s in hopes that he message will be received
    try {
      Thread.sleep(1000);
    } catch (InterruptedException ex) {
      // boo
    }
    
    nettyTransceiver.close();
    updateExecutor.shutdownNow();
    
    LOG.debug("Returning with code 0");
    return 0;
  }

  private boolean initializeService() {
    try {
      nettyTransceiver = new NettyTransceiver(masterAddr);
      masterService = SpecificRequestor.getClient(IterativeReduceService.class,
          nettyTransceiver);

      LOG.info("Connected to master via NettyTranseiver at " + masterAddr);

    } catch (IOException ex) {
      LOG.error("Unable to connect to master at " + masterAddr);

      return false;
    }

    if (!getConfiguration())
      return false;

    return true;
  }

  private boolean getConfiguration() {
    try {
      LOG.info("Checking in and downloading configuration from master");
      workerConf = masterService.startup(workerId);

      LOG.info("Recevied startup configuration from master" + ", fileSplit=["
          + workerConf.getSplit().getPath() + ", " + workerConf.getSplit().getOffset()
          + ", " + workerConf.getSplit().getLength() + "]" + ", batchSize="
          + workerConf.getBatchSize() + ", iterations=" + workerConf.getIterations());

    } catch (AvroRemoteException ex) {
      if (ex instanceof ServiceError) {
        LOG.error(
            "Unable to call startup(): " + ((ServiceError) ex).getDescription(),
            ex);
      } else {
        LOG.error("Unable to call startup()", ex);
      }

      return false;
    }
    
    // Merge configs and fire a startup call to compute
    Utils.mergeConfigs(workerConf, conf);
    computable.setup(conf);

    return true;
  }

  private int waitOnMasterUpdate(int lastUpdate) throws InterruptedException,
      AvroRemoteException {
    int nextUpdate = 0;
    long waitStarted = System.currentTimeMillis();
    long waitingFor = 0;

    while ((nextUpdate = masterService
        .waiting(workerId, lastUpdate, waitingFor)) < 0) {

      synchronized (currentState) {
        currentState = WorkerState.WAITING;
      }

      Thread.sleep(updateSleepTime);
      waitingFor = System.currentTimeMillis() - waitStarted;

      LOG.debug("Waiting on update from master for " + waitingFor + "ms");
      
      mWaits++;
    }
    
    mWaitTime += waitingFor;

    return nextUpdate;
  }

  private ProgressReport createProgressReport() {
    if (progressReport == null) {
      progressReport = new ProgressReport();
      progressReport.setWorkerId(workerId);
    }

    // Create a new report
    Map<CharSequence, CharSequence> report = new HashMap<CharSequence, CharSequence>();

    synchronized (progressCounters) {
      for (Map.Entry<String, Integer> entry : progressCounters.entrySet()) {
        report.put(entry.getKey(), String.valueOf(entry.getValue()));
      }
    }

    progressReport.setReport(report);

    if (LOG.isDebugEnabled()) {
      StringBuffer sb = new StringBuffer();
      sb.append("Created a progress report");
      sb.append(", workerId=").append(
          Utils.getWorkerId(progressReport.getWorkerId()));

      for (Map.Entry<CharSequence, CharSequence> entry : progressReport
          .getReport().entrySet()) {
        sb.append(", ").append(entry.getKey()).append("=")
            .append(entry.getValue());
      }

      LOG.debug(sb.toString());
    }

    return progressReport;
  }
  
  private void reportMetrics() {
    Map<CharSequence, Long> report = new HashMap<CharSequence, Long>();
    report.put("ComputableWorkerTime", mWorkerTime);
    report.put("ComputableWorkerExecutions", mWorkerExecutions);
    report.put("WaitCount", mWaits);
    report.put("WaitTime", mWaitTime);
    report.put("UpdatesSent", mUpdates);
  
    masterService.metricsReport(workerId, report);
  }
}
