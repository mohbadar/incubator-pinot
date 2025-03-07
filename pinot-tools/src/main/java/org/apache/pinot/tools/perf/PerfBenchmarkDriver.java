/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.tools.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.commons.configuration.BaseConfiguration;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.tools.ClusterVerifiers.StrictMatchExternalViewVerifier;
import org.apache.pinot.broker.broker.helix.HelixBrokerStarter;
import org.apache.pinot.common.config.TableConfig;
import org.apache.pinot.common.config.TableNameBuilder;
import org.apache.pinot.common.config.Tenant;
import org.apache.pinot.common.config.Tenant.TenantBuilder;
import org.apache.pinot.common.segment.SegmentMetadata;
import org.apache.pinot.common.utils.CommonConstants;
import org.apache.pinot.common.utils.FileUploadDownloadClient;
import org.apache.pinot.common.utils.JsonUtils;
import org.apache.pinot.common.utils.TenantRole;
import org.apache.pinot.controller.ControllerConf;
import org.apache.pinot.controller.ControllerStarter;
import org.apache.pinot.controller.helix.core.PinotHelixResourceManager;
import org.apache.pinot.server.starter.helix.HelixServerStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;


@SuppressWarnings("FieldCanBeLocal")
public class PerfBenchmarkDriver {
  private static final Logger LOGGER = LoggerFactory.getLogger(PerfBenchmarkDriver.class);
  private static final long BROKER_TIMEOUT_MS = 60_000L;

  private final PerfBenchmarkDriverConf _conf;
  private final String _zkAddress;
  private final String _clusterName;
  private final String _tempDir;
  private final String _loadMode;
  private final String _segmentFormatVersion;
  private final boolean _verbose;

  private ControllerStarter _controllerStarter;
  private String _controllerHost;
  private int _controllerPort;
  private String _controllerAddress;
  private String _controllerDataDir;

  private String _brokerBaseApiUrl;

  private String _serverInstanceDataDir;
  private String _serverInstanceSegmentTarDir;
  private String _serverInstanceName;

  // TODO: read from configuration.
  private final int _numReplicas = 1;
  private final String _segmentAssignmentStrategy = "BalanceNumSegmentAssignmentStrategy";
  private final String _brokerTenantName = "DefaultTenant";
  private final String _serverTenantName = "DefaultTenant";

  // Used for creating tables and tenants, and uploading segments. Since uncompressed segments are already available
  // for PerfBenchmarkDriver, servers can directly load the segments. PinotHelixResourceManager.addNewSegment(), which
  // updates ZKSegmentMetadata only, is not exposed from controller API so we need to update segments directly via
  // PinotHelixResourceManager.
  private PinotHelixResourceManager _helixResourceManager;

  public PerfBenchmarkDriver(PerfBenchmarkDriverConf conf) {
    this(conf, "/tmp/", "HEAP", null, false);
  }

  public PerfBenchmarkDriver(PerfBenchmarkDriverConf conf, String tempDir, String loadMode, String segmentFormatVersion,
      boolean verbose) {
    _conf = conf;
    _zkAddress = conf.getZkHost() + ":" + conf.getZkPort();
    _clusterName = conf.getClusterName();
    if (tempDir.endsWith("/")) {
      _tempDir = tempDir;
    } else {
      _tempDir = tempDir + '/';
    }
    _loadMode = loadMode;
    _segmentFormatVersion = segmentFormatVersion;
    _verbose = verbose;
    init();
  }

  private void init() {
    // Init controller.
    String controllerHost = _conf.getControllerHost();
    if (controllerHost != null) {
      _controllerHost = controllerHost;
    } else {
      _controllerHost = "localhost";
    }
    int controllerPort = _conf.getControllerPort();
    if (controllerPort > 0) {
      _controllerPort = controllerPort;
    } else {
      _controllerPort = 8300;
    }
    _controllerAddress = _controllerHost + ":" + _controllerPort;
    String controllerDataDir = _conf.getControllerDataDir();
    if (controllerDataDir != null) {
      _controllerDataDir = controllerDataDir;
    } else {
      _controllerDataDir = _tempDir + "controller/" + _controllerAddress + "/controller_data_dir";
    }

    // Init broker.
    _brokerBaseApiUrl = "http://" + _conf.getBrokerHost() + ":" + _conf.getBrokerPort();

    // Init server.
    String serverInstanceName = _conf.getServerInstanceName();
    if (serverInstanceName != null) {
      _serverInstanceName = serverInstanceName;
    } else {
      _serverInstanceName = "Server_localhost_" + CommonConstants.Helix.DEFAULT_SERVER_NETTY_PORT;
    }
    String serverInstanceDataDir = _conf.getServerInstanceDataDir();
    if (serverInstanceDataDir != null) {
      _serverInstanceDataDir = serverInstanceDataDir;
    } else {
      _serverInstanceDataDir = _tempDir + "server/" + _serverInstanceName + "/index_data_dir";
    }
    String serverInstanceSegmentTarDir = _conf.getServerInstanceSegmentTarDir();
    if (serverInstanceSegmentTarDir != null) {
      _serverInstanceSegmentTarDir = serverInstanceSegmentTarDir;
    } else {
      _serverInstanceSegmentTarDir = _tempDir + "server/" + _serverInstanceName + "/segment_tar_dir";
    }
  }

  public void run()
      throws Exception {
    startZookeeper();
    startController();
    startBroker();
    startServer();
    startHelixResourceManager();
    configureResources();
    waitForExternalViewUpdate(_zkAddress, _clusterName, 60 * 1000L);
    postQueries();
  }

  private void startZookeeper()
      throws Exception {
    int zkPort = _conf.getZkPort();
    if (!_conf.isStartZookeeper()) {
      LOGGER.info("Skipping start zookeeper step. Assumes zookeeper is already started.");
      return;
    }
    ZookeeperLauncher launcher = new ZookeeperLauncher(_tempDir);
    launcher.start(zkPort);
  }

  private void startController() {
    if (!_conf.shouldStartController()) {
      LOGGER.info("Skipping start controller step. Assumes controller is already started.");
      return;
    }
    ControllerConf conf = getControllerConf();
    LOGGER.info("Starting controller at {}", _controllerAddress);
    _controllerStarter = new ControllerStarter(conf);
    _controllerStarter.start();
  }

  private ControllerConf getControllerConf() {
    ControllerConf conf = new ControllerConf();
    conf.setHelixClusterName(_clusterName);
    conf.setZkStr(_zkAddress);
    conf.setControllerHost(_controllerHost);
    conf.setControllerPort(String.valueOf(_controllerPort));
    conf.setDataDir(_controllerDataDir);
    conf.setTenantIsolationEnabled(false);
    conf.setControllerVipHost("localhost");
    conf.setControllerVipProtocol("http");
    return conf;
  }

  private void startBroker()
      throws Exception {
    if (!_conf.shouldStartBroker()) {
      LOGGER.info("Skipping start broker step. Assumes broker is already started.");
      return;
    }
    Configuration brokerConf = new BaseConfiguration();
    String brokerInstanceName = "Broker_localhost_" + CommonConstants.Helix.DEFAULT_BROKER_QUERY_PORT;
    brokerConf.setProperty(CommonConstants.Helix.Instance.INSTANCE_ID_KEY, brokerInstanceName);
    brokerConf.setProperty(CommonConstants.Broker.CONFIG_OF_BROKER_TIMEOUT_MS, BROKER_TIMEOUT_MS);
    LOGGER.info("Starting broker instance: {}", brokerInstanceName);
    new HelixBrokerStarter(brokerConf, _clusterName, _zkAddress).start();
  }

  private void startServer()
      throws Exception {
    if (!_conf.shouldStartServer()) {
      LOGGER.info("Skipping start server step. Assumes server is already started.");
      return;
    }
    Configuration serverConfiguration = new PropertiesConfiguration();
    serverConfiguration.addProperty(CommonConstants.Server.CONFIG_OF_INSTANCE_DATA_DIR, _serverInstanceDataDir);
    serverConfiguration
        .addProperty(CommonConstants.Server.CONFIG_OF_INSTANCE_SEGMENT_TAR_DIR, _serverInstanceSegmentTarDir);
    if (_segmentFormatVersion != null) {
      serverConfiguration.setProperty(CommonConstants.Server.CONFIG_OF_SEGMENT_FORMAT_VERSION, _segmentFormatVersion);
    }
    serverConfiguration.setProperty(CommonConstants.Helix.Instance.INSTANCE_ID_KEY, _serverInstanceName);
    LOGGER.info("Starting server instance: {}", _serverInstanceName);
    new HelixServerStarter(_clusterName, _zkAddress, serverConfiguration);
  }

  private void startHelixResourceManager()
      throws Exception {
    if (_conf.shouldStartController()) {
      // helix resource manager is already available at this time if controller is started
      _helixResourceManager = _controllerStarter.getHelixResourceManager();
    } else {
      // When starting server only, we need to change the controller port to avoid registering controller helix
      // participant with the same host and port.
      ControllerConf controllerConf = getControllerConf();
      controllerConf.setControllerPort(Integer.toString(_conf.getControllerPort() + 1));
      _helixResourceManager = new PinotHelixResourceManager(controllerConf);
      _helixResourceManager.start();
    }

    // Create server tenants if required
    if (_conf.shouldStartServer()) {
      Tenant serverTenant =
          new TenantBuilder(_serverTenantName).setRole(TenantRole.SERVER).setTotalInstances(1).setOfflineInstances(1)
              .build();
      _helixResourceManager.createServerTenant(serverTenant);
    }

    // Create broker tenant if required
    if (_conf.shouldStartBroker()) {
      Tenant brokerTenant = new TenantBuilder(_brokerTenantName).setRole(TenantRole.BROKER).setTotalInstances(1).build();
      _helixResourceManager.createBrokerTenant(brokerTenant);
    }
  }

  private void configureResources()
      throws Exception {
    if (!_conf.isConfigureResources()) {
      LOGGER.info("Skipping configure resources step.");
      return;
    }
    String tableName = _conf.getTableName();
    configureTable(tableName);
  }

  public void configureTable(String tableName)
      throws Exception {
    configureTable(tableName, null, null);
  }

  public void configureTable(String tableName, List<String> invertedIndexColumns, List<String> bloomFilterColumns)
      throws Exception {
    TableConfig tableConfig = new TableConfig.Builder(CommonConstants.Helix.TableType.OFFLINE).setTableName(tableName)
        .setSegmentAssignmentStrategy(_segmentAssignmentStrategy).setNumReplicas(_numReplicas)
        .setBrokerTenant(_brokerTenantName).setServerTenant(_serverTenantName).setLoadMode(_loadMode)
        .setSegmentVersion(_segmentFormatVersion).setInvertedIndexColumns(invertedIndexColumns)
        .setBloomFilterColumns(bloomFilterColumns).build();
    _helixResourceManager.addTable(tableConfig);
  }

  /**
   * Add segment while segment data is already in server data directory.
   *
   * @param segmentMetadata segment metadata.
   */
  public void addSegment(String tableName, SegmentMetadata segmentMetadata) {
    String rawTableName = TableNameBuilder.extractRawTableName(tableName);
    _helixResourceManager
        .addNewSegment(rawTableName, segmentMetadata, "http://" + _controllerAddress + "/" + segmentMetadata.getName());
  }

  public static void waitForExternalViewUpdate(String zkAddress, final String clusterName, long timeoutInMilliseconds) {
    final ZKHelixAdmin helixAdmin = new ZKHelixAdmin(zkAddress);

    List<String> allResourcesInCluster = helixAdmin.getResourcesInCluster(clusterName);
    Set<String> tableAndBrokerResources = new HashSet<>();
    for (String resourceName : allResourcesInCluster) {
      // Only check table resources and broker resource
      if (TableNameBuilder.isTableResource(resourceName) || resourceName
          .equals(CommonConstants.Helix.BROKER_RESOURCE_INSTANCE)) {
        tableAndBrokerResources.add(resourceName);
      }
    }

    StrictMatchExternalViewVerifier verifier =
        new StrictMatchExternalViewVerifier.Builder(clusterName).setZkAddr(zkAddress)
            .setResources(tableAndBrokerResources).build();

    boolean success = verifier.verify(timeoutInMilliseconds);
    if (success) {
      LOGGER.info("Cluster is ready to serve queries");
    }
  }

  private void postQueries()
      throws Exception {
    if (!_conf.isRunQueries()) {
      LOGGER.info("Skipping run queries step.");
      return;
    }
    String queriesDirectory = _conf.getQueriesDirectory();
    File[] queryFiles = new File(queriesDirectory).listFiles();
    Preconditions.checkNotNull(queryFiles);
    for (File queryFile : queryFiles) {
      if (!queryFile.getName().endsWith(".txt")) {
        continue;
      }
      LOGGER.info("Running queries from: {}", queryFile);
      try (BufferedReader reader = new BufferedReader(new FileReader(queryFile))) {
        String query;
        while ((query = reader.readLine()) != null) {
          postQuery(query);
        }
      }
    }
  }

  public JsonNode postQuery(String query)
      throws Exception {
    return postQuery(query, null);
  }

  public JsonNode postQuery(String query, String optimizationFlags)
      throws Exception {
    ObjectNode requestJson = JsonUtils.newObjectNode();
    requestJson.put("pql", query);

    if (optimizationFlags != null && !optimizationFlags.isEmpty()) {
      requestJson.put("debugOptions", "optimizationFlags=" + optimizationFlags);
    }

    long start = System.currentTimeMillis();
    URLConnection conn = new URL(_brokerBaseApiUrl + "/query").openConnection();
    conn.setDoOutput(true);

    try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream(), "UTF-8"))) {
      String requestString = requestJson.toString();
      writer.write(requestString);
      writer.flush();

      StringBuilder stringBuilder = new StringBuilder();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
        String line;
        while ((line = reader.readLine()) != null) {
          stringBuilder.append(line);
        }
      }

      long totalTime = System.currentTimeMillis() - start;
      String responseString = stringBuilder.toString();
      ObjectNode responseJson = (ObjectNode) JsonUtils.stringToJsonNode(responseString);
      responseJson.put("totalTime", totalTime);

      if (_verbose && (responseJson.get("numDocsScanned").asLong() > 0)) {
        LOGGER.info("requestString: {}", requestString);
        LOGGER.info("responseString: {}", responseString);
      }
      return responseJson;
    }
  }

  /**
   * Start cluster components with default configuration.
   *
   * @param isStartZookeeper whether to start zookeeper.
   * @param isStartController whether to start controller.
   * @param isStartBroker whether to start broker.
   * @param isStartServer whether to start server.
   * @return perf benchmark driver.
   * @throws Exception
   */
  public static PerfBenchmarkDriver startComponents(boolean isStartZookeeper, boolean isStartController,
      boolean isStartBroker, boolean isStartServer, @Nullable String serverDataDir)
      throws Exception {
    PerfBenchmarkDriverConf conf = new PerfBenchmarkDriverConf();
    conf.setStartZookeeper(isStartZookeeper);
    conf.setStartController(isStartController);
    conf.setStartBroker(isStartBroker);
    conf.setStartServer(isStartServer);
    conf.setServerInstanceDataDir(serverDataDir);
    PerfBenchmarkDriver driver = new PerfBenchmarkDriver(conf);
    driver.run();
    return driver;
  }

  public static void main(String[] args)
      throws Exception {
    PerfBenchmarkDriverConf conf = (PerfBenchmarkDriverConf) new Yaml().load(new FileInputStream(args[0]));
    PerfBenchmarkDriver perfBenchmarkDriver = new PerfBenchmarkDriver(conf);
    perfBenchmarkDriver.run();
  }
}
