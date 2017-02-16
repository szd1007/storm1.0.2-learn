/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;

import org.apache.storm.hooks.SubmitterHookException;
import org.apache.storm.scheduler.resource.ResourceUtils;
import org.apache.storm.validation.ConfigValidation;
import org.apache.commons.lang.StringUtils;
import org.apache.thrift.TException;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.storm.security.auth.IAutoCredentials;
import org.apache.storm.security.auth.AuthUtils;
import org.apache.storm.generated.*;
import org.apache.storm.utils.BufferFileInputStream;
import org.apache.storm.utils.NimbusClient;
import org.apache.storm.utils.Utils;

/**
 * Use this class to submit topologies to run on the Storm cluster. You should run your program
 * with the "storm jar" command from the command-line, and then use this class to
 * submit your topologies.
 */
public class StormSubmitter {
    public static final Logger LOG = LoggerFactory.getLogger(StormSubmitter.class);

    private static final int THRIFT_CHUNK_SIZE_BYTES = 307200;/**300Kb*/

    private static ILocalCluster localNimbus = null;/**本地模式*/

    private static String generateZookeeperDigestSecretPayload() {
        return Utils.secureRandomLong() + ":" + Utils.secureRandomLong();
    }

    public static final Pattern zkDigestPattern = Pattern.compile("\\S+:\\S+");

    public static boolean validateZKDigestPayload(String payload) {
        if (payload != null) {
            Matcher m = zkDigestPattern.matcher(payload);
            return m.matches();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static Map prepareZookeeperAuthentication(Map conf) {/**zk配置相关，如果配置文件没有则创建*/
        Map toRet = new HashMap();

        // Is the topology ZooKeeper authentication configuration unset?
        if (! conf.containsKey(Config.STORM_ZOOKEEPER_TOPOLOGY_AUTH_PAYLOAD) ||
                conf.get(Config.STORM_ZOOKEEPER_TOPOLOGY_AUTH_PAYLOAD) == null ||
                !  validateZKDigestPayload((String)
                    conf.get(Config.STORM_ZOOKEEPER_TOPOLOGY_AUTH_PAYLOAD))) {

            String secretPayload = generateZookeeperDigestSecretPayload();
            toRet.put(Config.STORM_ZOOKEEPER_TOPOLOGY_AUTH_PAYLOAD, secretPayload);
            LOG.info("Generated ZooKeeper secret payload for MD5-digest: " + secretPayload);
        }

        // This should always be set to digest.
        toRet.put(Config.STORM_ZOOKEEPER_TOPOLOGY_AUTH_SCHEME, "digest");

        return toRet;
    }
    /**证书相关  通过反射生成配置的IAutoCredentials接口实现类 */
    private static Map<String,String> populateCredentials(Map conf, Map<String, String> creds) {
        Map<String,String> ret = new HashMap<>();
        for (IAutoCredentials autoCred: AuthUtils.GetAutoCredentials(conf)) {
            LOG.info("Running "+autoCred);
            autoCred.populateCredentials(ret);
        }
        if (creds != null) {
            ret.putAll(creds);
        }
        return ret;
    }

    /**
     * Push a new set of credentials to the running topology.
     * @param name the name of the topology to push credentials to.
     * @param stormConf the topology-specific configuration, if desired. See {@link Config}.
     * @param credentials the credentials to push.
     * @throws AuthorizationException if you are not authorized ot push credentials.
     * @throws NotAliveException if the topology is not alive
     * @throws InvalidTopologyException if any other error happens
     */
    public static void pushCredentials(String name, Map stormConf, Map<String, String> credentials)
            throws AuthorizationException, NotAliveException, InvalidTopologyException {
        stormConf = new HashMap(stormConf);
        stormConf.putAll(Utils.readCommandLineOpts());
        Map conf = Utils.readStormConfig();/**读取配置文件 storm.yaml */
        conf.putAll(stormConf);
        Map<String,String> fullCreds = populateCredentials(conf, credentials);/**获取增加证书信息*/
        if (fullCreds.isEmpty()) {
            LOG.warn("No credentials were found to push to " + name);
            return;
        }
        try {
            if(localNimbus!=null) { /**本地模式*/
                LOG.info("Pushing Credentials to topology " + name + " in local mode");
                localNimbus.uploadNewCredentials(name, new Credentials(fullCreds));
            } else {
                NimbusClient client = NimbusClient.getConfiguredClient(conf);/**和nimbus的leader建立连接*/
                try {
                    LOG.info("Uploading new credentials to " +  name);
                    client.getClient().uploadNewCredentials(name, new Credentials(fullCreds));/**调用rpc方法，服务端实现是clj代码*/
                } finally {
                    client.close();
                }
            }
            LOG.info("Finished submitting topology: " +  name);
        } catch(TException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Submits a topology to run on the cluster. A topology runs forever or until
     * explicitly killed.  向集群提交topology
     *
     *
     * @param name the name of the storm.
     * @param stormConf the topology-specific configuration. See {@link Config}.
     * @param topology the processing to execute.
     * @throws AlreadyAliveException if a topology with this name is already running
     * @throws InvalidTopologyException if an invalid topology was submitted
     * @throws AuthorizationException if authorization is failed
     * @thorws SubmitterHookException if any Exception occurs during initialization or invocation of registered {@link ISubmitterHook}
     */  /**提供一个大而全的方法，和一批指定默认参数的方法，给用户暴露尽量少的内部参数*/
    public static void submitTopology(String name, Map stormConf, StormTopology topology)
            throws AlreadyAliveException, InvalidTopologyException, AuthorizationException {
        submitTopology(name, stormConf, topology, null, null);
    }

    /**
     * Submits a topology to run on the cluster. A topology runs forever or until
     * explicitly killed.
     *
     * @param name the name of the storm.
     * @param stormConf the topology-specific configuration. See {@link Config}.
     * @param topology the processing to execute.
     * @param opts to manipulate the starting of the topology.
     * @throws AlreadyAliveException if a topology with this name is already running
     * @throws InvalidTopologyException if an invalid topology was submitted
     * @throws AuthorizationException if authorization is failed
     * @thorws SubmitterHookException if any Exception occurs during initialization or invocation of registered {@link ISubmitterHook}
     */
    public static void submitTopology(String name, Map stormConf, StormTopology topology, SubmitOptions opts)
            throws AlreadyAliveException, InvalidTopologyException, AuthorizationException {
        submitTopology(name, stormConf, topology, opts, null);
    }

    /**
     * Submits a topology to run on the cluster as a particular user. A topology runs forever or until
     * explicitly killed.
     *
     * @param name
     * @param stormConf
     * @param topology
     * @param opts
     * @param progressListener
     * @param asUser The user as which this topology should be submitted.
     * @throws AlreadyAliveException
     * @throws InvalidTopologyException
     * @throws AuthorizationException
     * @throws IllegalArgumentException thrown if configs will yield an unschedulable topology. validateConfs validates confs
     * @thorws SubmitterHookException if any Exception occurs during initialization or invocation of registered {@link ISubmitterHook}
     */
    public static void submitTopologyAs(String name, Map stormConf, StormTopology topology, SubmitOptions opts, ProgressListener progressListener, String asUser)
            throws AlreadyAliveException, InvalidTopologyException, AuthorizationException, IllegalArgumentException {
        if(!Utils.isValidConf(stormConf)) {
            throw new IllegalArgumentException("Storm conf is not valid. Must be json-serializable");
        }/**读配置*/
        stormConf = new HashMap(stormConf);
        stormConf.putAll(Utils.readCommandLineOpts());
        Map conf = Utils.readStormConfig();
        conf.putAll(stormConf);
        stormConf.putAll(prepareZookeeperAuthentication(conf));
        /**验证配置是否正确，其中需要查看提交的最大堆参数是否小于配置的值*/
        validateConfs(conf, topology);

        Map<String,String> passedCreds = new HashMap<>();
        if (opts != null) {
            Credentials tmpCreds = opts.get_creds();
            if (tmpCreds != null) {
                passedCreds = tmpCreds.get_creds();
            }
        }
        Map<String,String> fullCreds = populateCredentials(conf, passedCreds);
        if (!fullCreds.isEmpty()) {
            if (opts == null) {
                opts = new SubmitOptions(TopologyInitialStatus.ACTIVE);
            }
            opts.set_creds(new Credentials(fullCreds));/**取到的证书信息存到stormOption中*/
        }
        try {
            if(localNimbus!=null) {/**这是从哪里初始化的？*/
                LOG.info("Submitting topology " + name + " in local mode");
                if(opts!=null) {
                    localNimbus.submitTopologyWithOpts(name, stormConf, topology, opts);
                } else {
                    // this is for backwards compatibility
                    localNimbus.submitTopology(name, stormConf, topology);
                }
                LOG.info("Finished submitting topology: " +  name);
            } else {/**远程调用*/
                String serConf = JSONValue.toJSONString(stormConf);
                if(topologyNameExists(conf, name, asUser)) {/**判断是否已经存在相同名称的topology*/
                    throw new RuntimeException("Topology with name `" + name + "` already exists on cluster");
                }
                String jar = submitJarAs(conf, System.getProperty("storm.jar"), progressListener, asUser);
                try {
                    LOG.info("Submitting topology " + name + " in distributed mode with conf " + serConf);
                    NimbusClient client = NimbusClient.getConfiguredClientAs(conf, asUser);
                    if (opts != null) {
                        client.getClient().submitTopologyWithOpts(name, jar, serConf, topology, opts);
                    } else {
                        // this is for backwards compatibility
                        client.getClient().submitTopology(name, jar, serConf, topology);/**提交topology任务*/
                    }
                    LOG.info("Finished submitting topology: " + name);
                } catch (InvalidTopologyException e) {
                    LOG.warn("Topology submission exception: " + e.get_msg());
                    throw e;
                } catch (AlreadyAliveException e) {
                    LOG.warn("Topology already alive exception", e);
                    throw e;
                }
            }
        } catch(TException e) {
            throw new RuntimeException(e);
        }
        invokeSubmitterHook(name, asUser, conf, topology);

    }

    /**
     *调用用户提供的钩子函数
     * @param name
     * @param asUser
     * @param stormConf
     * @param topology
     *
     * @thorws SubmitterHookException This is thrown when any Exception occurs during initialization or invocation of registered {@link ISubmitterHook}
     */
    private static void invokeSubmitterHook(String name, String asUser, Map stormConf, StormTopology topology) {
        String submissionNotifierClassName = null;
        try {
            if (stormConf.containsKey(Config.STORM_TOPOLOGY_SUBMISSION_NOTIFIER_PLUGIN)) {
                submissionNotifierClassName = stormConf.get(Config.STORM_TOPOLOGY_SUBMISSION_NOTIFIER_PLUGIN).toString();
                LOG.info("Initializing the registered ISubmitterHook [{}]", submissionNotifierClassName);

                if(submissionNotifierClassName == null || submissionNotifierClassName.isEmpty()) {
                    throw new IllegalArgumentException(Config.STORM_TOPOLOGY_SUBMISSION_NOTIFIER_PLUGIN + " property must be a non empty string.");
                }

                ISubmitterHook submitterHook = (ISubmitterHook) Class.forName(submissionNotifierClassName).newInstance();
                TopologyInfo topologyInfo = Utils.getTopologyInfo(name, asUser, stormConf);/**rpc取topology信息*/
                LOG.info("Invoking the registered ISubmitterHook [{}]", submissionNotifierClassName);
                submitterHook.notify(topologyInfo, stormConf, topology);/**调用接口函数*/
            }
        } catch (Exception e) {
            LOG.warn("Error occurred in invoking submitter hook:[{}] ",submissionNotifierClassName, e);
            throw new SubmitterHookException(e);
        }
    }

    /**
     * Submits a topology to run on the cluster. A topology runs forever or until
     * explicitly killed.
     *
     *
     * @param name the name of the storm.
     * @param stormConf the topology-specific configuration. See {@link Config}.
     * @param topology the processing to execute.
     * @param opts to manipulate the starting of the topology
     * @param progressListener to track the progress of the jar upload process
     * @throws AlreadyAliveException if a topology with this name is already running
     * @throws InvalidTopologyException if an invalid topology was submitted
     * @throws AuthorizationException if authorization is failed
     * @thorws SubmitterHookException if any Exception occurs during initialization or invocation of registered {@link ISubmitterHook}
     */
    @SuppressWarnings("unchecked")
    public static void submitTopology(String name, Map stormConf, StormTopology topology, SubmitOptions opts,
             ProgressListener progressListener) throws AlreadyAliveException, InvalidTopologyException, AuthorizationException {
        submitTopologyAs(name, stormConf, topology, opts, progressListener, null);
    }

    /**
     * Submits a topology to run on the cluster with a progress bar. A topology runs forever or until
     * explicitly killed.
     *
     *
     * @param name the name of the storm.
     * @param stormConf the topology-specific configuration. See {@link Config}.
     * @param topology the processing to execute.
     * @throws AlreadyAliveException if a topology with this name is already running
     * @throws InvalidTopologyException if an invalid topology was submitted
     * @throws AuthorizationException if authorization is failed
     */

    public static void submitTopologyWithProgressBar(String name, Map stormConf, StormTopology topology) throws AlreadyAliveException, InvalidTopologyException, AuthorizationException {
        submitTopologyWithProgressBar(name, stormConf, topology, null);
    }

    /**
     * Submits a topology to run on the cluster with a progress bar. A topology runs forever or until
     * explicitly killed.
     *默认实现了progressListener接口类中的方法，提供了默认实现
     *
     * @param name the name of the storm.
     * @param stormConf the topology-specific configuration. See {@link Config}.
     * @param topology the processing to execute.
     * @param opts to manipulate the starting of the topology
     * @throws AlreadyAliveException if a topology with this name is already running
     * @throws InvalidTopologyException if an invalid topology was submitted
     * @throws AuthorizationException if authorization is failed
     * @thorws SubmitterHookException if any Exception occurs during initialization or invocation of registered {@link ISubmitterHook}
     */
    public static void submitTopologyWithProgressBar(String name, Map stormConf, StormTopology topology, SubmitOptions opts) throws AlreadyAliveException, InvalidTopologyException, AuthorizationException {
        // show a progress bar so we know we're not stuck (especially on slow connections)
        submitTopology(name, stormConf, topology, opts, new StormSubmitter.ProgressListener() {
            @Override
            public void onStart(String srcFile, String targetFile, long totalBytes) {
                System.out.printf("Start uploading file '%s' to '%s' (%d bytes)\n", srcFile, targetFile, totalBytes);
            }

            @Override
            public void onProgress(String srcFile, String targetFile, long bytesUploaded, long totalBytes) {
                int length = 50;
                int p = (int)((length * bytesUploaded) / totalBytes);
                String progress = StringUtils.repeat("=", p);
                String todo = StringUtils.repeat(" ", length - p);

                System.out.printf("\r[%s%s] %d / %d", progress, todo, bytesUploaded, totalBytes);
            }

            @Override
            public void onCompleted(String srcFile, String targetFile, long totalBytes) {
                System.out.printf("\nFile '%s' uploaded to '%s' (%d bytes)\n", srcFile, targetFile, totalBytes);
            }
        });
    }
    /**远程调用*/
    private static boolean topologyNameExists(Map conf, String name, String asUser) {
        NimbusClient client = NimbusClient.getConfiguredClientAs(conf, asUser);
        try {
            ClusterSummary summary = client.getClient().getClusterInfo();
            for(TopologySummary s : summary.get_topologies()) {
                if(s.get_name().equals(name)) {
                    return true;
                }
            }
            return false;

        } catch(Exception e) {
            throw new RuntimeException(e);
        } finally {
            client.close();
        }
    }

    private static String submitJar(Map conf, ProgressListener listener) {
        return  submitJar(conf, System.getProperty("storm.jar"), listener);
    }

    /**
     * Submit jar file  提交jar文件
     * @param conf the topology-specific configuration. See {@link Config}.
     * @param localJar file path of the jar file to submit
     * @return the remote location of the submitted jar
     */
    public static String submitJar(Map conf, String localJar) {
        return submitJar(conf, localJar, null);
    }


    public static String submitJarAs(Map conf, String localJar, ProgressListener listener, String asUser) {
        if (localJar == null) {
            throw new RuntimeException("Must submit topologies using the 'storm' client script so that StormSubmitter knows which jar to upload.");
        }

        NimbusClient client = NimbusClient.getConfiguredClientAs(conf, asUser);
        try {
            String uploadLocation = client.getClient().beginFileUpload();/**获取nimbus上存储上传jar包的目录，具体逻辑service实现*/
            LOG.info("Uploading topology jar " + localJar + " to assigned location: " + uploadLocation);
            BufferFileInputStream is = new BufferFileInputStream(localJar, THRIFT_CHUNK_SIZE_BYTES);

            long totalSize = new File(localJar).length();
            if (listener != null) {/**回调接口中的相关方法，具体类实现可以传入*/
                listener.onStart(localJar, uploadLocation, totalSize);
            }

            long bytesUploaded = 0;
            while(true) {/**循环上传数据，每次上传固定块大小*/
                byte[] toSubmit = is.read();
                bytesUploaded += toSubmit.length;
                if (listener != null) {
                    listener.onProgress(localJar, uploadLocation, bytesUploaded, totalSize);
                }

                if(toSubmit.length==0) break;/**thrift的binary类型对应ByteBuffer对象*/
                client.getClient().uploadChunk(uploadLocation, ByteBuffer.wrap(toSubmit));
            }
            client.getClient().finishFileUpload(uploadLocation);

            if (listener != null) {
                listener.onCompleted(localJar, uploadLocation, totalSize);
            }

            LOG.info("Successfully uploaded topology jar to assigned location: " + uploadLocation);
            return uploadLocation;
        } catch(Exception e) {
            throw new RuntimeException(e);
        } finally {
            client.close();
        }
    }

    /**
     * Submit jar file
     * @param conf the topology-specific configuration. See {@link Config}.
     * @param localJar file path of the jar file to submit
     * @param listener progress listener to track the jar file upload
     * @return the remote location of the submitted jar
     */
    public static String submitJar(Map conf, String localJar, ProgressListener listener) {
        return submitJarAs(conf,localJar, listener, null);
    }

    /**
     * Interface use to track progress of file upload
     */
    public interface ProgressListener {
        /**
         * called before file is uploaded
         * @param srcFile - jar file to be uploaded
         * @param targetFile - destination file
         * @param totalBytes - total number of bytes of the file
         */
        public void onStart(String srcFile, String targetFile, long totalBytes);

        /**
         * called whenever a chunk of bytes is uploaded
         * @param srcFile - jar file to be uploaded
         * @param targetFile - destination file
         * @param bytesUploaded - number of bytes transferred so far
         * @param totalBytes - total number of bytes of the file
         */
        public void onProgress(String srcFile, String targetFile, long bytesUploaded, long totalBytes);

        /**
         * called when the file is uploaded
         * @param srcFile - jar file to be uploaded
         * @param targetFile - destination file
         * @param totalBytes - total number of bytes of the file
         */
        public void onCompleted(String srcFile, String targetFile, long totalBytes);
    }

    private static void validateConfs(Map stormConf, StormTopology topology) throws IllegalArgumentException {
        ConfigValidation.validateFields(stormConf);
        validateTopologyWorkerMaxHeapSizeMBConfigs(stormConf, topology);
    }

    private static void validateTopologyWorkerMaxHeapSizeMBConfigs(Map stormConf, StormTopology topology) {
        double largestMemReq = getMaxExecutorMemoryUsageForTopo(topology, stormConf);
        Double topologyWorkerMaxHeapSize = Utils.getDouble(stormConf.get(Config.TOPOLOGY_WORKER_MAX_HEAP_SIZE_MB));
        if(topologyWorkerMaxHeapSize < largestMemReq) {
            throw new IllegalArgumentException("Topology will not be able to be successfully scheduled: Config TOPOLOGY_WORKER_MAX_HEAP_SIZE_MB="
                    +Utils.getDouble(stormConf.get(Config.TOPOLOGY_WORKER_MAX_HEAP_SIZE_MB)) + " < "
                    + largestMemReq + " (Largest memory requirement of a component in the topology). Perhaps set TOPOLOGY_WORKER_MAX_HEAP_SIZE_MB to a larger amount");
        }
    }

    private static double getMaxExecutorMemoryUsageForTopo(StormTopology topology, Map topologyConf) {
        double largestMemoryOperator = 0.0;
        for(Map<String, Double> entry : ResourceUtils.getBoltsResources(topology, topologyConf).values()) {
            double memoryRequirement = entry.get(Config.TOPOLOGY_COMPONENT_RESOURCES_OFFHEAP_MEMORY_MB)
                    + entry.get(Config.TOPOLOGY_COMPONENT_RESOURCES_ONHEAP_MEMORY_MB);
            if(memoryRequirement > largestMemoryOperator) {
                largestMemoryOperator = memoryRequirement;
            }
        }
        for(Map<String, Double> entry : ResourceUtils.getSpoutsResources(topology, topologyConf).values()) {
            double memoryRequirement = entry.get(Config.TOPOLOGY_COMPONENT_RESOURCES_OFFHEAP_MEMORY_MB)
                    + entry.get(Config.TOPOLOGY_COMPONENT_RESOURCES_ONHEAP_MEMORY_MB);
            if(memoryRequirement > largestMemoryOperator) {
                largestMemoryOperator = memoryRequirement;
            }
        }
        return largestMemoryOperator;
    }
}
