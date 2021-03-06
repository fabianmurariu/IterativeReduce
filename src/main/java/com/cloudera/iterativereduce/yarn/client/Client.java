package com.cloudera.iterativereduce.yarn.client;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;

import com.cloudera.iterativereduce.ConfigFields;
import com.cloudera.iterativereduce.Utils;
import com.cloudera.iterativereduce.yarn.ResourceManagerHandler;

public class Client extends Configured implements Tool {

  private static final Log LOG = LogFactory.getLog(Client.class);
  
  @Override
  public int run(String[] args) throws Exception {
    if (args.length < 1)
      LOG.info("No configuration file specified, using default ("
          + ConfigFields.DEFAULT_CONFIG_FILE + ")");
    
    long startTime = System.currentTimeMillis();
    String configFile = (args.length < 1) ? ConfigFields.DEFAULT_CONFIG_FILE : args[0];
    Properties props = new Properties();
    Configuration conf = getConf();

    try {
      FileInputStream fis = new FileInputStream(configFile);
      props.load(fis);
    } catch (FileNotFoundException ex) {
      throw ex; // TODO: be nice
    } catch (IOException ex) {
      throw ex; // TODO: be nice
    }
    
    // Make sure we have some bare minimums
    ConfigFields.validateConfig(props);
    
    if (LOG.isDebugEnabled()) {
      LOG.debug("Loaded configuration: ");
      for (Map.Entry<Object, Object> entry : props.entrySet()) {
        LOG.debug(entry.getKey() + "=" + entry.getValue());
      }
    }

    // TODO: make sure input file(s), libs, etc. actually exist!
    // Ensure our input path exists
    
    Path p = new Path(props.getProperty(ConfigFields.APP_INPUT_PATH));
    FileSystem fs = FileSystem.get(conf);
    
    if (!fs.exists(p))
      throw new FileNotFoundException("Input path not found: " + p.toString()
          + " (in " + fs.getUri() + ")");

    LOG.info("Using input path: " + p.toString());
    
    // Connect
    ResourceManagerHandler rmHandler = new ResourceManagerHandler(conf, null);
    rmHandler.getClientResourceManager();

    // Create an Application request/ID
    ApplicationId appId = rmHandler.getApplicationId(); // Our AppId
    String appName = props.getProperty(ConfigFields.APP_NAME,
        ConfigFields.DEFAULT_APP_NAME).replace(' ', '_');
    
    LOG.info("Got an application, id=" + appId + ", appName=" + appName);
    
    // Copy resources to [HD]FS
    LOG.debug("Copying resources to filesystem");
    Utils.copyLocalResourcesToFs(props, conf, appId, appName); // Local resources
    Utils.copyLocalResourceToFs(configFile, ConfigFields.APP_CONFIG_FILE, conf,
        appId, appName); // Config file
    
    try {
      Utils.copyLocalResourceToFs("log4j.properties", "log4j.properties", conf,
          appId, appName); // Log4j
    } catch (FileNotFoundException ex) {
      LOG.warn("log4j.properties file not found");
    }

    // Create our context
    List<String> commands = Utils.getMasterCommand(conf, props);
    Map<String, LocalResource> localResources = Utils
        .getLocalResourcesForApplication(conf, appId, appName, props,
            LocalResourceVisibility.APPLICATION);

    // Submit app
    rmHandler.submitApplication(appId, appName, Utils.getEnvironment(conf, props), 
        localResources, commands,
        Integer.parseInt(props.getProperty(ConfigFields.YARN_MEMORY, "512")));    

    // Wait for app to complete
    while (true) {
      Thread.sleep(2000);
      
      ApplicationReport report = rmHandler.getApplicationReport(appId);
      LOG.info("Got applicaton report for"
          + ", appId=" + appId.getId()
          + ", state=" + report.getYarnApplicationState().toString()
          + ", amDiag=" + report.getDiagnostics()
          + ", masterHost=" + report.getHost()
          + ", masterRpcPort=" + report.getRpcPort()
          + ", queue=" + report.getQueue()
          + ", startTime=" + report.getStartTime()
          + ", clientToken=" + report.getClientToken() 
          + ", finalState=" + report.getFinalApplicationStatus().toString()
          + ", trackingUrl=" + report.getTrackingUrl()
          + ", user=" + report.getUser());

      if (YarnApplicationState.FINISHED == report.getYarnApplicationState()) {
        LOG.info("Application finished in " + (System.currentTimeMillis() - startTime) + "ms");

        if (FinalApplicationStatus.SUCCEEDED == report.getFinalApplicationStatus()) {
          LOG.info("Application completed succesfully.");
          return 0;
        } else {
          LOG.info("Application completed with en error: " + report.getDiagnostics());
          return -1;
        }
      } else if (YarnApplicationState.FAILED == report.getYarnApplicationState() ||
          YarnApplicationState.KILLED == report.getYarnApplicationState()) {

        LOG.info("Application completed with a failed or killed state: " + report.getDiagnostics());
        return -1; 
      }
    }
  }
  
  public static void main(String[] args) throws Exception {
    int rc = ToolRunner.run(new Configuration(), new Client(), args);
    
    // Log, because been bitten before on daemon threads; sanity check
    LOG.debug("Calling System.exit(" + rc + ")");
    System.exit(rc);
  }
}
